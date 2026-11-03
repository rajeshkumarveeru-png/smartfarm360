export type Permission =
  | 'TENANT_READ' | 'TENANT_MANAGE' | 'MEMBERS_READ' | 'MEMBERS_MANAGE' | 'AUDIT_READ'
  | 'FARM_READ' | 'FARM_WRITE' | 'CROP_READ' | 'CROP_WRITE' | 'CROP_ACTIVITY_WRITE'
  | 'FINANCE_READ' | 'FINANCE_WRITE' | 'FINANCE_REVERSE' | 'FINANCE_MANAGE' | 'INVENTORY_READ' | 'INVENTORY_WRITE'
  | 'WORKFORCE_READ' | 'WORKFORCE_WRITE' | 'PAYROLL_MANAGE' | 'ASSETS_READ' | 'ASSETS_WRITE'
  | 'LIVESTOCK_READ' | 'LIVESTOCK_WRITE' | 'AQUACULTURE_READ' | 'AQUACULTURE_WRITE' | 'ORGANIC_READ' | 'ORGANIC_WRITE' | 'PROCESSING_READ' | 'PROCESSING_WRITE'
  | 'BILLING_VIEW' | 'BILLING_MANAGE';

export type ModuleCode = 'CROP' | 'DAIRY' | 'POULTRY' | 'GOAT_SHEEP' | 'AQUACULTURE' | 'ORGANIC' | 'PROCESSING' | 'WORKFORCE' | 'ASSETS';

export type RoleCode = 'TENANT_OWNER' | 'FARM_ADMIN' | 'FARM_MANAGER' | 'ACCOUNTANT' | 'FIELD_SUPERVISOR' | 'WORKER' | 'VIEWER';

export const ROLE_CODES: RoleCode[] = ['TENANT_OWNER', 'FARM_ADMIN', 'FARM_MANAGER', 'ACCOUNTANT', 'FIELD_SUPERVISOR', 'WORKER', 'VIEWER'];

export type TenantSummary = {
  id: number; name: string; slug: string; preferredLanguage: 'en' | 'ta'; active: boolean;
  roles: RoleCode[]; permissions: Permission[]; enabledModules: ModuleCode[]; createdAt: string;
  suspendedReason?: string | null; deletionScheduledFor?: string | null;
};

export type Settings = { timezone: string; currencyCode: string; areaUnit: string; weightUnit: string; volumeUnit: string };
export type ModuleStatus = { code: ModuleCode; enabled: boolean; available: boolean };
export type TenantDetail = {
  id: number; name: string; slug: string; preferredLanguage: 'en' | 'ta'; planCode: string; active: boolean; createdAt: string;
  settings: Settings; modules: ModuleStatus[];
};

export type Farm = { id: number; tenantId: number; name: string; farmType: string; village?: string; district?: string; state?: string; country: string; active: boolean };
export type Parcel = { id: number; tenantId: number; farmId: number; name: string; area: number; areaUnit: string; soilType?: string; irrigationType?: string; active: boolean };

export type Member = { membershipId: number; userId: number; email: string; displayName: string; roles: RoleCode[]; joinedAt: string };
export type Invitation = { id: number; email: string; roles: RoleCode[]; expiresAt: string; createdAt: string; acceptUrl?: string | null };

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };
export type AuditEntry = { id: number; actorUserId?: number; actorEmail?: string; action: string; entityType: string; entityId?: string; details: Record<string, unknown>; occurredAt: string };
export type PlatformTenant = { id: number; name: string; slug: string; active: boolean; planCode: string; memberCount: number; farmCount: number;
  modules: string[]; lastActivity: string | null; createdAt: string; suspendedReason: string | null; deletionScheduledFor: string | null };
export type Usage = { limit: 'MEMBERS' | 'FARMS' | 'EXPORTS_PER_DAY'; used: number; max: number; nearLimit: boolean; overridden: boolean };
export type HistoryEntry = { at: string; action: string; actorUserId: number | null; actorEmail: string | null; details: string };
export type GrantRow = { id: number; grantedByEmail: string; grantedToEmail: string | null; reason: string; startsAt: string; endsAt: string; endedAt: string | null };
export type PlatformTenantDetail = { tenant: PlatformTenant; owners: { userId: number; email: string; displayName: string }[]; usage: Usage[];
  history: HistoryEntry[]; supportGrants: GrantRow[] };
export type PlatformUser = { id: number; email: string; displayName: string; active: boolean; platformAdmin: boolean; emailVerified: boolean; twoStep: boolean;
  createdAt: string; memberships: { tenantId: number; tenantName: string; roles: string[] }[] };
export type OpsCheck = { kind: string; ok: boolean; details: string | null; durationMs: number | null; recordedAt: string };
export type Operations = { version: string; lastBackup: OpsCheck | null; lastRestoreTest: OpsCheck | null; lastAlertTest: OpsCheck | null;
  lastLoadTest: OpsCheck | null; alertEmailSet: boolean };
export type PlatformAuditEntry = { id: number; at: string; actorUserId: number | null; actorEmail: string | null; action: string; targetType: string;
  targetId: number | null; details: string };
export type Announcement = { id: number; messageEn: string; messageTa: string; startsAt: string; endsAt: string; maintenance: boolean; endedAt: string | null };
export type LegalDocument = { kind: 'TERMS' | 'PRIVACY'; version: number; language: 'en' | 'ta'; title: string; body: string; changes: string | null; publishedAt: string };
export type Consent = { kind: 'TERMS' | 'PRIVACY' | 'MARKETING'; version: number; accepted: boolean; client: string; recordedAt: string };
export type SupportGrant = { id: number; grantedByEmail: string; grantedToEmail: string | null; reason: string; startsAt: string; endsAt: string;
  endedAt: string | null; active: boolean };
export type TenantExport = { id: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED'; sizeBytes: number | null; requestedAt: string;
  readyAt: string | null; expiresAt: string | null; requestedByEmail: string };
export type DeletionState = { pending: boolean; requestedAt: string | null; scheduledFor: string | null; requestedByEmail: string | null };

// ---- Billing and payments (billing design, Phase 15) ----------------------------------------------------------------

export type BillingPeriod = 'MONTHLY' | 'YEARLY';
export type SubscriptionState = 'NONE' | 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'LAPSED' | 'CANCELLED' | 'ENDED';

export interface PlanPrice { amount: number; validFrom: string }

export interface BillingPlan {
  code: string; name: string; paid: boolean; sortOrder: number;
  limits: { code: string; value: number | null }[];
  prices: { period: BillingPeriod; current: PlanPrice | null; next: PlanPrice | null }[];
}

export interface BillingOverview {
  state: SubscriptionState; planCode: string; period: BillingPeriod | null; effectivePlanCode: string;
  trialEndsAt: string | null; paidUntil: string | null; graceEndsAt: string | null;
  pendingPlanCode: string | null; pendingPeriod: BillingPeriod | null; compPlanCode: string | null; compUntil: string | null;
  checkoutStarted: boolean; upgradeStarted: boolean; profileComplete: boolean;
  banner: 'TRIAL_ENDING' | 'PAST_DUE' | 'LAPSED' | null; plans: BillingPlan[];
}

export interface CheckoutResult { checkoutUrl: string | null; overview: BillingOverview }

export interface BillingProfile {
  legalName: string; addressLine1: string; addressLine2: string | null; city: string; pincode: string; stateCode: string;
  gstin: string | null; email: string; updatedAt?: string;
}

export interface CreditNote { id: number; number: string; noteDate: string; reason: string; taxableAmount: number; cgst: number; sgst: number; igst: number; total: number }

export interface BillingInvoice {
  id: number; number: string; invoiceDate: string; description: string; planCode: string; period: BillingPeriod;
  periodStart: string; periodEnd: string; taxableAmount: number; cgst: number; sgst: number; igst: number; total: number;
  refundedAmount: number; buyerGstin: string | null; bookedDocumentId: number | null; creditNotes: CreditNote[];
}

export interface ConsoleSubscription {
  tenantId: number; tenantName: string; state: SubscriptionState; planCode: string; period: BillingPeriod | null; effectivePlanCode: string;
  trialEndsAt: string | null; paidUntil: string | null; graceEndsAt: string | null; compPlanCode: string | null; compUntil: string | null; compReason: string | null;
}

export interface ConsolePayment {
  id: number; tenantId: number; businessName: string; kind: 'RENEWAL' | 'UPGRADE'; amount: number; refundedAmount: number; method: string | null;
  capturedAt: string; invoiceId: number | null; invoiceNumber: string | null;
}

export interface GatewayEventRow { id: number; eventId: string; eventType: string; tenantId: number | null; receivedAt: string; outcome: string; error: string | null }

export interface ReconciliationRun {
  id: number; ranAt: string; ok: boolean; error: string | null;
  differences: { kind: string; gatewayId: string; tenantId: number | null; detail: string }[];
}

export interface BillingSummary { subscriptions: Record<string, number>; unappliedEvents: GatewayEventRow[]; lastReconciliation: ReconciliationRun | null }

export interface PriceRow { planCode: string; period: BillingPeriod; amount: number; validFrom: string }
