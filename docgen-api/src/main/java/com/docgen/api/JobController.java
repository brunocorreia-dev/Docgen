package com.docgen.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobController {
    private final JobService jobs;
    private final ProjectService projects;

    JobController(JobService jobs, ProjectService projects) {
        this.jobs = jobs;
        this.projects = projects;
    }

    @GetMapping("/api/v1/jobs/{jobId}")
    JobService.JobView get(@PathVariable("jobId") String jobId) {
        return jobs.get(CurrentUser.username(), jobId);
    }

    @PostMapping("/api/v1/jobs/{jobId}/cancellation")
    JobService.JobView cancel(@PathVariable("jobId") String jobId) {
        return jobs.cancel(CurrentUser.username(), jobId);
    }

    @GetMapping("/api/v1/projects/{projectId}/jobs")
    JobService.JobList list(@PathVariable("projectId") String projectId) {
        String owner = CurrentUser.username();
        return jobs.listForProject(owner, projects.require(owner, projectId).id());
    }
}
