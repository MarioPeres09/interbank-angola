import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { Account, AccountMovement, banks, CreditReceipt, Transfer, TransferRequest } from '../models/bank';
import { BankApi, apiError } from '../services/bank-api';
import { Session } from '../services/session';

type View = 'overview' | 'accounts' | 'transfer' | 'history' | 'receipt';
type Attempt = { id: string; request: TransferRequest };

const uuidPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const accountPattern = /^[A-Za-z0-9-]{1,32}$/;
const amountPattern = /^(?:0|[1-9]\d{0,16})(?:\.\d{1,2})?$/;
const money = new Intl.NumberFormat('pt-AO', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

@Component({
  selector: 'app-bank-dashboard',
  imports: [DatePipe, FormsModule, NgTemplateOutlet],
  templateUrl: './bank-dashboard.html',
  styleUrl: './bank-dashboard.css',
})
export class BankDashboard implements OnInit {
  private readonly api = inject(BankApi);
  protected readonly session = inject(Session);
  protected readonly bank = this.session.bank();
  protected readonly local = banks[this.bank];
  protected readonly peer = banks[this.local.peer];
  private readonly storageKey = `interbank.active-transfer.${this.bank}.${this.session.customer()?.customerId}`;
  protected readonly view = signal<View>('overview');
  protected readonly accounts = signal<Account[]>([]);
  protected readonly transfers = signal<Transfer[]>([]);
  protected readonly totalAvailable = computed(() =>
    this.accounts().reduce((sum, account) => sum + account.availableBalance, 0),
  );
  protected readonly activeAccounts = computed(() =>
    this.accounts().filter((account) => account.status === 'ACTIVE'),
  );
  protected readonly pendingCount = computed(() =>
    this.transfers().filter((transfer) => transfer.status === 'PENDING').length,
  );
  protected readonly loading = signal(false);
  protected readonly accountError = signal('');
  protected readonly movementAccount = signal<Account | null>(null);
  protected readonly movements = signal<AccountMovement[]>([]);
  protected readonly movementError = signal('');
  protected readonly movementLoading = signal(false);
  protected readonly historyError = signal('');
  protected readonly sending = signal(false);
  protected readonly transferError = signal('');
  protected readonly result = signal<Transfer | null>(null);
  protected readonly attempt = signal<Attempt | null>(this.readAttempt());
  protected readonly receipt = signal<CreditReceipt | null>(null);
  protected readonly receiptLoading = signal(false);
  protected readonly receiptError = signal('');
  protected source: number | null = null;
  protected destination = '';
  protected destinationType: 'account' | 'phone' = 'account';
  protected phone = '';
  protected readonly resolvedAccount = signal('');
  protected readonly phoneLoading = signal(false);
  protected readonly phoneError = signal('');
  protected amount = '';
  protected receiptId = '';

  ngOnInit(): void {
    void this.refresh();
  }

  protected open(view: View): void {
    this.view.set(view);
  }

  protected async refresh(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.accountError.set('');
    this.historyError.set('');
    try {
      await Promise.all([
        firstValueFrom(this.api.accounts()).then((accounts) => {
          this.accounts.set(accounts);
          if (!this.activeAccounts().some((account) => account.id === this.source)) {
            this.source = this.activeAccounts()[0]?.id ?? null;
          }
        }).catch((error) => this.accountError.set(apiError(error))),
        firstValueFrom(this.api.transfers()).then((transfers) => {
          this.transfers.set(transfers);
          const attempt = this.attempt();
          const updated = transfers.find((transfer) => transfer.id === attempt?.id);
          if (updated && updated.status !== 'PENDING' && !this.sending()) {
            this.result.set(updated);
            this.saveAttempt(null);
          }
        })
          .catch((error) => this.historyError.set(apiError(error))),
      ]);
    } finally {
      this.loading.set(false);
    }
  }

  protected format(value: number): string {
    return money.format(value) + ' Kz';
  }

  protected async showMovements(account: Account): Promise<void> {
    this.movementAccount.set(account);
    this.movements.set([]);
    this.movementError.set('');
    this.movementLoading.set(true);
    try {
      this.movements.set(await firstValueFrom(this.api.movements(account.id)));
    } catch (error) {
      this.movementError.set(apiError(error, { 404: 'A conta já não está disponível.' }));
    } finally {
      this.movementLoading.set(false);
    }
  }

  protected async submit(): Promise<void> {
    if (this.attempt() || this.sending() || this.phoneLoading()) return;
    const amount = this.amount.trim().replace(',', '.');
    const destination = this.destinationType === 'phone'
      ? this.resolvedAccount() : this.destination.trim().toUpperCase();
    if (this.source === null || !accountPattern.test(destination)
        || !amountPattern.test(amount) || !/[1-9]/.test(amount)) {
      this.transferError.set('Escolhe uma conta de origem e indica um destino e um montante positivo válido.');
      return;
    }
    await this.send({
      id: crypto.randomUUID(),
      request: { sourceAccountId: this.source, destinationAccount: destination, amount },
    });
  }

  protected updatePhone(value: string): void {
    this.phone = value;
    this.resolvedAccount.set('');
    this.phoneError.set('');
  }

  protected async lookupPhone(): Promise<void> {
    const phone = this.phone.trim();
    if (this.phoneLoading() || !phone) return;
    this.resolvedAccount.set('');
    this.phoneError.set('');
    this.phoneLoading.set(true);
    try {
      const destination = await firstValueFrom(this.api.resolvePhone(phone));
      if (this.phone.trim() === phone) this.resolvedAccount.set(destination.accountNumber);
    } catch (error) {
      this.phoneError.set(apiError(error, {
        400: 'Indica um número de telemóvel válido, com ou sem +244.',
        404: 'Este telemóvel não tem uma conta activa no banco de destino.',
        502: 'O banco de destino está indisponível. Tenta novamente.',
      }));
    } finally {
      this.phoneLoading.set(false);
    }
  }

  protected async retry(): Promise<void> {
    const attempt = this.attempt();
    if (attempt) await this.send(attempt);
  }

  private async send(attempt: Attempt): Promise<void> {
    if (this.sending()) return;
    this.sending.set(true);
    this.transferError.set('');
    this.result.set(null);
    this.saveAttempt(attempt);
    try {
      const transfer = await firstValueFrom(this.api.send(attempt.id, attempt.request));
      this.result.set(transfer);
      if (transfer.status !== 'PENDING') {
        this.saveAttempt(null);
        this.destination = '';
        this.updatePhone('');
        this.amount = '';
      }
    } catch (error) {
      if (error instanceof HttpErrorResponse && [400, 404, 409, 422].includes(error.status)) {
        this.saveAttempt(null);
      }
      this.transferError.set(apiError(error, {
        400: 'Verifica os dados da transferência.',
        404: 'A conta de origem não foi encontrada.',
        409: 'Esta referência já foi usada com outros dados. Confirma o histórico.',
        422: 'Saldo disponível insuficiente ou conta de origem inactiva.',
      }));
    } finally {
      this.sending.set(false);
      void this.refresh();
    }
  }

  protected async lookupReceipt(): Promise<void> {
    if (this.receiptLoading()) return;
    const id = this.receiptId.trim();
    this.receipt.set(null);
    this.receiptError.set('');
    if (!uuidPattern.test(id)) {
      this.receiptError.set('Introduz a referência completa da transferência.');
      return;
    }
    this.receiptLoading.set(true);
    try {
      this.receipt.set(await firstValueFrom(this.api.receipt(id)));
    } catch (error) {
      this.receiptError.set(apiError(error, { 404: 'Nenhum recibo encontrado para esta referência.' }));
    } finally {
      this.receiptLoading.set(false);
    }
  }

  protected viewReceipt(transfer: Transfer): void {
    this.receiptId = transfer.id;
    this.open('receipt');
    void this.lookupReceipt();
  }

  private readAttempt(): Attempt | null {
    try {
      const previousKey = `interbank.active-transfer.${this.session.customer()?.customerId}`;
      let stored = sessionStorage.getItem(this.storageKey);
      if (!stored && this.bank === 'kwanza') {
        stored = sessionStorage.getItem(previousKey);
        if (stored) {
          sessionStorage.setItem(this.storageKey, stored);
          sessionStorage.removeItem(previousKey);
        }
      }
      return stored ? JSON.parse(stored) as Attempt : null;
    } catch {
      return null;
    }
  }

  private saveAttempt(attempt: Attempt | null): void {
    this.attempt.set(attempt);
    try {
      if (attempt) sessionStorage.setItem(this.storageKey, JSON.stringify(attempt));
      else sessionStorage.removeItem(this.storageKey);
    } catch {
      // The current tab still retains the request if browser storage is unavailable.
    }
  }
}
