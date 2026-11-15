package com.docgen.api;

import com.docgen.ProjectBrief;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
class ProjectService {
    static final int MAX_PROJECTS_PER_USER = 200;

    private final ProjectRepository projects;
    private final JobService jobs;
    private final Clock clock;

    ProjectService(ProjectRepository projects, JobService jobs, Clock clock) {
        this.projects = projects;
        this.jobs = jobs;
        this.clock = clock;
    }

    ProjectDtos.ProjectView create(String owner, ProjectDtos.ProjectRequest request) {
        if (projects.count(owner) >= MAX_PROJECTS_PER_USER) {
            throw new CapacityExceededException("project_limit", "The project limit was reached.");
        }
        Instant now = clock.instant();
        ProjectRepository.ProjectRecord project = new ProjectRepository.ProjectRecord(
                Ids.random(), owner, brief(request), request.provider(), blankToNull(request.model()), now, now);
        projects.insert(project);
        return view(project);
    }

    ProjectDtos.ProjectView update(String owner, String projectId, ProjectDtos.ProjectRequest request) {
        ProjectRepository.ProjectRecord current = require(owner, projectId);
        ProjectRepository.ProjectRecord updated = new ProjectRepository.ProjectRecord(current.id(), owner,
                brief(request), request.provider(), blankToNull(request.model()), current.createdAt(), clock.instant());
        if (!projects.update(updated)) {
            throw new ResourceNotFoundException();
        }
        return view(updated);
    }

    ProjectDtos.ProjectView get(String owner, String projectId) {
        return view(require(owner, projectId));
    }

    ProjectDtos.ProjectList list(String owner) {
        List<ProjectDtos.ProjectSummary> summaries = projects.list(owner).stream()
                .map(item -> new ProjectDtos.ProjectSummary(
                        item.project().id(),
                        item.project().brief().name(),
                        preview(item.project().brief().description()),
                        item.project().provider(),
                        item.project().model(),
                        item.project().createdAt(),
                        item.project().updatedAt(),
                        stats(item.stats())))
                .toList();
        return new ProjectDtos.ProjectList(summaries);
    }

    void delete(String owner, String projectId) {
        ProjectRepository.ProjectRecord project = require(owner, projectId);
        jobs.cancelProjectJobs(owner, project.id());
        if (!projects.delete(project.id(), owner)) {
            throw new ResourceNotFoundException();
        }
    }

    ProjectRepository.ProjectRecord require(String owner, String projectId) {
        return projects.find(Ids.require(projectId), owner).orElseThrow(ResourceNotFoundException::new);
    }

    void touch(String owner, String projectId) {
        projects.touch(projectId, owner, clock.instant());
    }

    private ProjectDtos.ProjectView view(ProjectRepository.ProjectRecord project) {
        ProjectBrief brief = project.brief();
        return new ProjectDtos.ProjectView(project.id(), brief.name(), brief.description(),
                brief.functionalRequirements(), brief.nonFunctionalRequirements(), brief.preferredTechnologies(),
                brief.constraints(), project.provider(), project.model(), brief.outputLanguage().tag(),
                project.createdAt(), project.updatedAt(), stats(projects.stats(project.id(), project.owner())));
    }

    private static ProjectDtos.ProjectStatsView stats(ProjectRepository.ProjectStats stats) {
        return new ProjectDtos.ProjectStatsView(stats.attachments(), stats.plans(), stats.approvedPlans(),
                stats.artifacts(), stats.activeJobs());
    }

    private static ProjectBrief brief(ProjectDtos.ProjectRequest request) {
        try {
            return new ProjectBrief(request.name(), request.description(), request.functionalRequirements(),
                    request.nonFunctionalRequirements(), request.preferredTechnologies(), request.constraints(),
                    request.outputLanguage() == null ? ProjectBrief.OutputLanguage.PT_BR
                            : ProjectBrief.OutputLanguage.fromTag(request.outputLanguage()));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        }
    }

    private static String preview(String description) {
        String line = description.replace('\n', ' ');
        return line.length() <= 220 ? line : line.substring(0, 219) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
