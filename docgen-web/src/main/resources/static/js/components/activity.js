// Job timeline with live status, elapsed time and cancellation.
import { h } from '../dom.js';
import { icon } from '../icons.js';
import { providerLabel, relativeTime } from '../format.js';
import { JOB_STATUS, JOB_TYPE, jobErrorMessage } from '../messages.js';
import { elapsedSeconds } from '../jobs.js';

const STATUS_ICON = { QUEUED: 'clock', RUNNING: 'refresh', SUCCEEDED: 'check', FAILED: 'alert', CANCELLED: 'stop' };

export function activityList(jobs, { onCancel } = {}) {
  if (!jobs.length) {
    return h('p', { class: 'muted activity-empty' }, 'Nenhuma interação com a IA ainda.');
  }
  return h('ol', { class: 'activity' }, jobs.map((job) => {
    const active = job.status === 'QUEUED' || job.status === 'RUNNING';
    return h('li', { class: ['activity-item', `status-${job.status.toLowerCase()}`] },
      h('span', { class: 'activity-icon' }, icon(STATUS_ICON[job.status] || 'info', { size: 15 })),
      h('div', { class: 'activity-body' },
        h('div', { class: 'activity-title' },
          h('strong', null, JOB_TYPE[job.type] || job.type),
          h('span', { class: 'activity-status' }, JOB_STATUS[job.status] || job.status)),
        h('small', { class: 'muted' }, [
          providerLabel(job.provider),
          job.model,
          relativeTime(job.createdAt),
          job.status === 'RUNNING' || job.finishedAt ? `${elapsedSeconds(job)} s` : null,
        ].filter(Boolean).join(' · ')),
        job.error ? h('p', { class: 'activity-error' }, jobErrorMessage(job.error)) : null,
        active ? h('div', { class: 'progress progress-indeterminate' }) : null),
      active && onCancel ? h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => onCancel(job) } },
        icon('stop', { size: 14 }), h('span', null, 'Cancelar')) : null);
  }));
}
