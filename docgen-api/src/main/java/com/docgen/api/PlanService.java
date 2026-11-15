package com.docgen.api;

import com.docgen.ArchitecturePlan;
import com.docgen.ArchitecturePlanJson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;

@Service
class PlanService {
    static final String ORIGIN = "AI_GENERATED";

    private final PlanRepository plans;
    private final ProjectService projects;
    private final JsonColumns json;
    private final Clock clock;

    PlanService(PlanRepository plans, ProjectService projects, JsonColumns json, Clock clock) {
        this.plans = plans;
        this.projects = projects;
        this.json = json;
        this.clock = clock;
    }

    WorkflowDtos.PlanList list(String owner, String projectId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        return new WorkflowDtos.PlanList(plans.list(project.id(), owner).stream().map(record -> {
            ArchitecturePlan plan = read(record);
            String summary = plan.summary().length() <= 240 ? plan.summary() : plan.summary().substring(0, 239) + "…";
            return new WorkflowDtos.PlanSummary(record.id(), record.status(), record.provider(), record.model(),
                    record.createdAt(), record.approvedAt(), summary, plan.components().size());
        }).toList());
    }

    WorkflowDtos.PlanView get(String owner, String projectId, String planId) {
        return view(require(owner, projectId, planId));
    }

    /** Human review gate: only approved plans can be turned into documentation. */
    WorkflowDtos.PlanView approve(String owner, String projectId, String planId) {
        PlanRepository.PlanRecord plan = require(owner, projectId, planId);
        if (plan.status() == PlanRepository.Status.APPROVED) {
            return view(plan);
        }
        if (!plans.approve(plan.id(), plan.projectId(), owner, clock.instant())) {
            throw new ConflictException("plan_state_changed", "The plan changed while it was being approved.");
        }
        projects.touch(owner, plan.projectId());
        return view(require(owner, projectId, planId));
    }

    PlanRepository.PlanRecord require(String owner, String projectId, String planId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        return plans.find(Ids.require(planId), project.id(), owner).orElseThrow(ResourceNotFoundException::new);
    }

    static ArchitecturePlan read(PlanRepository.PlanRecord record) {
        try {
            return ArchitecturePlanJson.read(record.planJson());
        } catch (IOException corrupted) {
            throw new IllegalStateException("Stored plan is unreadable");
        }
    }

    private WorkflowDtos.PlanView view(PlanRepository.PlanRecord record) {
        return new WorkflowDtos.PlanView(record.id(), record.projectId(), record.status(), record.provider(),
                record.model(), ORIGIN, true, record.createdAt(), record.approvedAt(), read(record),
                record.mermaid(), json.read(record.warningsJson()));
    }
}
