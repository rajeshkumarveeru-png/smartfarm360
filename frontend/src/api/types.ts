export type Permission =
  | 'TENANT_READ' | 'TENANT_MANAGE' | 'MEMBERS_READ' | 'MEMBERS_MANAGE' | 'AUDIT_READ'
  | 'FARM_READ' | 'FARM_WRITE' | 'CROP_READ' | 'CROP_WRITE' | 'CROP_ACTIVITY_WRITE'
  | 'FINANCE_READ' | 'FINANCE_WRITE' | 'FINANCE_REVERSE' | 'FINANCE_MANAGE' | 'INVENTORY_READ' | 'INVENTORY_WRITE'
  | 'WORKFORCE_READ' | 'WORKFORCE_WRITE' | 'PAYROLL_MANAGE' | 'ASSETS_READ' | 'ASSETS_WRITE'
  | 'LIVESTOCK_READ' | 'LIVESTOCK_WRITE' | 'AQUACULTURE_READ' | 'AQUACULTURE_WRITE' | 'ORGANIC_READ' | 'ORGANIC_WRITE' | 'PROCESSING_READ' | 'PROCESSING_WRITE';

export type ModuleCode = 'CROP' | 'DAIRY' | 'POULTRY' | 'GOAT_SHEEP' | 'AQUACULTURE' | 'ORGANIC' | 'PROCESSING' | 'WORKFORCE' | 'ASSETS';

export type RoleCode = 'TENANT_OWNER' | 'FARM_ADMIN' | 'FARM_MANAGER' | 'ACCOUNTANT' | 'FIELD_SUPERVISOR' | 'WORKER' | 'VIEWER';

export const ROLE_CODES: RoleCode[] = ['TENANT_OWNER', 'FARM_ADMIN', 'FARM_MANAGER', 'ACCOUNTANT', 'FIELD_SUPERVISOR', 'WORKER', 'VIEWER'];

export type TenantSummary = {
  id: number; name: string; slug: string; preferredLanguage: 'en' | 'ta'; active: boolean;
  roles: RoleCode[]; permissions: Permission[]; enabledModules: ModuleCode[]; createdAt: string;
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
export type PlatformTenant = { id: number; name: string; slug: string; active: boolean; planCode: string; memberCount: number; createdAt: string };
