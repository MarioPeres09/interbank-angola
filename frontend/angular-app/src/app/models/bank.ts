export const banks = {
  kwanza: { name: 'Banco Kwanza', initials: 'BK', example: 'BK-000123', api: '/api', peer: 'horizonte' },
  horizonte: { name: 'Banco Horizonte', initials: 'BH', example: 'BH-000245', api: '/horizonte/api', peer: 'kwanza' },
} as const;

export type Bank = keyof typeof banks;

export interface AccountMovement {
  id: number;
  transferId: string;
  type: 'DEBIT' | 'CREDIT';
  amount: number;
  createdAt: string;
}

export interface Account {
  id: number;
  accountNumber: string;
  balance: number;
  availableBalance: number;
  status: 'ACTIVE' | 'BLOCKED' | 'CLOSED';
}

export interface TransferRequest {
  sourceAccountId: number;
  destinationAccount: string;
  amount: string;
}

export interface Transfer extends Omit<TransferRequest, 'amount'> {
  id: string;
  destinationBank: 'KWANZA' | 'HORIZONTE';
  amount: number;
  currency: 'AOA';
  status: 'PENDING' | 'COMPLETED' | 'FAILED';
  createdAt: string;
}

export interface CreditReceipt {
  id: string;
  destinationAccount: string;
  amount: number;
  currency: 'AOA';
  status: 'CREDITED' | 'REJECTED';
  createdAt: string;
}
