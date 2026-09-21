export type ClaimStatus =
  | 'DRAFT'
  | 'REGISTERED'
  | 'UNDER_REVIEW'
  | 'PENDING_CORRECTION'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'INADMISSIBLE';
export type ClaimPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface ClaimSummary {
  id: number;
  reference: string;
  title: string;
  status: ClaimStatus;
  priority?: ClaimPriority;
  dueAt?: string | null;
  assignedToId?: number | null;
  assignedToUsername?: string | null;
  createdById: number;
  createdByUsername: string;
  createdAt: string;
  updatedAt: string;
}

export interface ClaimDetail {
  id: number;
  reference: string;
  title: string;
  description: string;
  status: ClaimStatus;
  priority?: ClaimPriority;
  dueAt?: string | null;
  assignedToId?: number | null;
  assignedToUsername?: string | null;
  assignedAt?: string | null;
  claimantName: string | null;
  createdById: number;
  createdByUsername: string;
  updatedById: number | null;
  updatedByUsername: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CreateClaimRequest {
  title: string;
  description: string;
  claimantName: string | null;
  priority?: ClaimPriority;
  dueAt?: string | null;
}

export interface UpdateClaimRequest {
  title: string;
  description: string;
  claimantName: string | null;
  priority?: ClaimPriority;
  dueAt?: string | null;
  version: number;
}

export interface ClaimFilters {
  search?: string | null;
  status?: ClaimStatus | '' | null;
  reference?: string | null;
  createdBy?: string | null;
  assignedTo?: string | null;
  priority?: ClaimPriority | '' | null;
  overdue?: boolean | null;
  createdFrom?: string | null;
  createdTo?: string | null;
  page?: number;
  size?: number;
  sort?: string;
}

export interface ClaimComment {
  id: number;
  body: string;
  authorId: number;
  authorUsername: string;
  createdAt: string;
}

export interface ClaimHistory {
  id: number;
  eventType: string;
  eventData: string | null;
  actorId: number;
  actorUsername: string;
  occurredAt: string;
}

export interface Reviewer {
  id: number;
  username: string;
  email: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
