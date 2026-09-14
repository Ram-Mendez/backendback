export type ClaimStatus =
  | 'DRAFT'
  | 'REGISTERED'
  | 'UNDER_REVIEW'
  | 'PENDING_CORRECTION'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'INADMISSIBLE';

export interface ClaimSummary {
  id: number;
  reference: string;
  title: string;
  status: ClaimStatus;
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
}

export interface UpdateClaimRequest {
  title: string;
  description: string;
  claimantName: string | null;
  version: number;
}

export interface ClaimFilters {
  search?: string | null;
  status?: ClaimStatus | '' | null;
  reference?: string | null;
  createdBy?: string | null;
  createdFrom?: string | null;
  createdTo?: string | null;
  page?: number;
  size?: number;
  sort?: string;
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
