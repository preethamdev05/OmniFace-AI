export type OrgType = 'SCHOOL' | 'COLLEGE' | 'COACHING' | 'CORPORATE' | 'GYM_EVENT';

export type EntityRole =
  | 'STUDENT'
  | 'FACULTY'
  | 'STAFF'
  | 'EMPLOYEE'
  | 'MANAGER'
  | 'MEMBER'
  | 'TRAINER'
  | 'CONTRACTOR'
  | 'VISITOR';

export interface OrgTerminologyConfig {
  orgType: OrgType;
  entityPlural: string;
  entitySingular: string;
  idLabel: string;
  groupLabel: string;
  allowedRoles: EntityRole[];
  defaultRole: EntityRole;
  filterCategories: {
    key: string;
    label: string;
    roles?: EntityRole[];
  }[];
}

const TERMINOLOGY_CONFIGS: Record<OrgType, OrgTerminologyConfig> = {
  SCHOOL: {
    orgType: 'SCHOOL',
    entityPlural: 'Students',
    entitySingular: 'Student',
    idLabel: 'Roll Number',
    groupLabel: 'Class / Section',
    allowedRoles: ['STUDENT', 'FACULTY', 'STAFF', 'VISITOR'],
    defaultRole: 'STUDENT',
    filterCategories: [
      { key: 'ALL', label: 'All People' },
      { key: 'PRIMARY', label: 'Students', roles: ['STUDENT'] },
      { key: 'STAFF', label: 'Faculty & Staff', roles: ['FACULTY', 'STAFF'] },
      { key: 'VISITOR', label: 'Visitors', roles: ['VISITOR'] },
    ],
  },
  COLLEGE: {
    orgType: 'COLLEGE',
    entityPlural: 'Students',
    entitySingular: 'Student',
    idLabel: 'Roll / Reg ID',
    groupLabel: 'Semester / Branch',
    allowedRoles: ['STUDENT', 'FACULTY', 'STAFF', 'VISITOR'],
    defaultRole: 'STUDENT',
    filterCategories: [
      { key: 'ALL', label: 'All People' },
      { key: 'PRIMARY', label: 'Students', roles: ['STUDENT'] },
      { key: 'STAFF', label: 'Faculty & Staff', roles: ['FACULTY', 'STAFF'] },
      { key: 'VISITOR', label: 'Visitors', roles: ['VISITOR'] },
    ],
  },
  COACHING: {
    orgType: 'COACHING',
    entityPlural: 'Learners',
    entitySingular: 'Learner',
    idLabel: 'Student ID',
    groupLabel: 'Batch / Course',
    allowedRoles: ['STUDENT', 'FACULTY', 'STAFF', 'VISITOR'],
    defaultRole: 'STUDENT',
    filterCategories: [
      { key: 'ALL', label: 'All People' },
      { key: 'PRIMARY', label: 'Learners', roles: ['STUDENT'] },
      { key: 'STAFF', label: 'Instructors & Staff', roles: ['FACULTY', 'STAFF'] },
      { key: 'VISITOR', label: 'Guests', roles: ['VISITOR'] },
    ],
  },
  CORPORATE: {
    orgType: 'CORPORATE',
    entityPlural: 'Employees',
    entitySingular: 'Employee',
    idLabel: 'Employee ID',
    groupLabel: 'Shift / Team',
    allowedRoles: ['EMPLOYEE', 'MANAGER', 'STAFF', 'CONTRACTOR', 'VISITOR'],
    defaultRole: 'EMPLOYEE',
    filterCategories: [
      { key: 'ALL', label: 'All Workforce' },
      { key: 'PRIMARY', label: 'Employees', roles: ['EMPLOYEE', 'STAFF'] },
      { key: 'MANAGEMENT', label: 'Leadership', roles: ['MANAGER'] },
      { key: 'EXTERNAL', label: 'Contractors & Visitors', roles: ['CONTRACTOR', 'VISITOR'] },
    ],
  },
  GYM_EVENT: {
    orgType: 'GYM_EVENT',
    entityPlural: 'Members',
    entitySingular: 'Member',
    idLabel: 'Member Pass ID',
    groupLabel: 'Tier / Plan',
    allowedRoles: ['MEMBER', 'TRAINER', 'STAFF', 'VISITOR'],
    defaultRole: 'MEMBER',
    filterCategories: [
      { key: 'ALL', label: 'All Members & Staff' },
      { key: 'PRIMARY', label: 'Members', roles: ['MEMBER'] },
      { key: 'STAFF', label: 'Trainers & Staff', roles: ['TRAINER', 'STAFF'] },
      { key: 'VISITOR', label: 'Guests', roles: ['VISITOR'] },
    ],
  },
};

export function getOrgTerminology(orgType?: string | null): OrgTerminologyConfig {
  const normalized = (orgType || 'SCHOOL').toUpperCase() as OrgType;
  return TERMINOLOGY_CONFIGS[normalized] || TERMINOLOGY_CONFIGS.SCHOOL;
}

export function getRoleBadgeMeta(role: string): { label: string; color: string; bg: string; border: string } {
  const r = (role || 'STUDENT').toUpperCase();
  switch (r) {
    case 'FACULTY':
    case 'INSTRUCTOR':
      return { label: 'Faculty', color: '#818CF8', bg: 'rgba(99, 102, 241, 0.12)', border: 'rgba(99, 102, 241, 0.3)' };
    case 'STAFF':
      return { label: 'Staff', color: '#38BDF8', bg: 'rgba(56, 189, 248, 0.12)', border: 'rgba(56, 189, 248, 0.3)' };
    case 'MANAGER':
      return { label: 'Manager', color: '#F59E0B', bg: 'rgba(245, 158, 11, 0.12)', border: 'rgba(245, 158, 11, 0.3)' };
    case 'EMPLOYEE':
      return { label: 'Employee', color: '#10B981', bg: 'rgba(16, 185, 129, 0.12)', border: 'rgba(16, 185, 129, 0.3)' };
    case 'CONTRACTOR':
      return { label: 'Contractor', color: '#EC4899', bg: 'rgba(236, 72, 153, 0.12)', border: 'rgba(236, 72, 153, 0.3)' };
    case 'MEMBER':
      return { label: 'Member', color: '#10B981', bg: 'rgba(16, 185, 129, 0.12)', border: 'rgba(16, 185, 129, 0.3)' };
    case 'TRAINER':
      return { label: 'Trainer', color: '#F97316', bg: 'rgba(249, 115, 22, 0.12)', border: 'rgba(249, 115, 22, 0.3)' };
    case 'VISITOR':
      return { label: 'Visitor', color: '#94A3B8', bg: 'rgba(148, 163, 184, 0.12)', border: 'rgba(148, 163, 184, 0.3)' };
    case 'STUDENT':
    default:
      return { label: 'Student', color: '#6366F1', bg: 'rgba(99, 102, 241, 0.12)', border: 'rgba(99, 102, 241, 0.3)' };
  }
}
