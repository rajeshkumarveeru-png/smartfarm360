import { tr, type Language } from '../i18n/translations';

/** The part of the plan the phone shows (billing design, P14). Paying and changing the plan happen on the web. */
export interface PlanStatus {
  state: string;
  effectivePlanCode: string;
  trialEndsAt: string | null;
  graceEndsAt: string | null;
  banner: 'TRIAL_ENDING' | 'PAST_DUE' | 'LAPSED' | null;
}

const day = (iso: string | null) => (iso ? iso.slice(0, 10) : '');

/** The banner every member sees, or null: the trial's last week, a failed renewal, or a lapsed plan. */
export function billingBanner(language: Language, status: PlanStatus | null): string | null {
  if (!status?.banner) return null;
  const date = status.banner === 'TRIAL_ENDING' ? status.trialEndsAt : status.graceEndsAt;
  return `${tr(language, `billing.banner.${status.banner}`, { date: day(date) })} ${tr(language, 'billing.managedOnWeb')}`;
}

/** One line for the More screen: the plan in force and its state. */
export function planLine(language: Language, status: PlanStatus): string {
  return `${tr(language, `billing.plan.${status.effectivePlanCode}`)} · ${tr(language, `billing.state.${status.state}`)}`;
}
