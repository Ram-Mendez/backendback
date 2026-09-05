export type ClaimStatus = 'DRAFT' | 'IN_REVIEW' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface Claim {
  id: number;
  claimant: string;
  provider: string;
  invoiceNumber: string;
  amount: number;
  status: ClaimStatus;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ClaimRequest {
  claimant: string;
  provider: string;
  invoiceNumber: string;
  amount: number;
  status: ClaimStatus;
  description: string | null;
}

export interface ClaimFilters {
  claimant?: string | null;
  provider?: string | null;
  invoiceNumber?: string | null;
  status?: ClaimStatus | '' | null;
  createdFrom?: string | null;
  createdTo?: string | null;
  page?: number;
  size?: number;
  sort?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}
