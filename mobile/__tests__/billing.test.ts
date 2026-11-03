import { billingBanner, planLine } from '../src/billing/billing';

const status = { state: 'TRIAL', effectivePlanCode: 'GROWER', trialEndsAt: '2026-12-01T00:00:00Z', graceEndsAt: null, banner: null } as const;

test('the phone shows the plan, and a banner only in the trial\'s last week, after a failed renewal or a lapse (P14)', () => {
  expect(billingBanner('en', status)).toBeNull();
  expect(billingBanner('en', null)).toBeNull();
  expect(billingBanner('en', { ...status, banner: 'TRIAL_ENDING' })).toBe(
    'Your trial ends on 2026-12-01. Choose a plan to keep its limits. The plan is managed on the SmartFarm360 website, in Settings.');
  expect(billingBanner('ta', { ...status, state: 'PAST_DUE', graceEndsAt: '2027-12-12T00:00:00Z', banner: 'PAST_DUE' }))
    .toContain('2027-12-12');
  expect(billingBanner('ta', { ...status, state: 'PAST_DUE', graceEndsAt: '2027-12-12T00:00:00Z', banner: 'PAST_DUE' }))
    .toContain('இணையதளத்தில்');
  expect(planLine('en', status)).toBe('Grower · Trial');
  expect(planLine('ta', { ...status, state: 'LAPSED', effectivePlanCode: 'FREE' })).toBe('இலவசம் · காலாவதியானது');
});
