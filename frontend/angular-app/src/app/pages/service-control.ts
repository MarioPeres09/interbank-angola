import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

type ServiceId = 'rede' | 'kwanza' | 'horizonte' | 'angular';
type State = 'off' | 'on' | 'starting' | 'stopping' | 'external' | 'error';
type Service = { id: ServiceId; port: number; state: State; message?: string };

@Component({
  selector: 'app-service-control',
  templateUrl: './service-control.html',
  styleUrl: './service-control.css',
})
export class ServiceControl implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private refreshing = false;
  protected readonly services = signal<Service[]>([]);
  protected readonly pending = signal<ServiceId | null>(null);
  protected readonly connectionError = signal('');
  protected readonly actionError = signal('');
  protected readonly online = computed(() =>
    this.services().filter(({ state }) => state === 'on' || state === 'external').length,
  );
  protected readonly labels: Record<State, string> = {
    off: 'Desligado', on: 'Online', starting: 'A arrancar', stopping: 'A parar',
    external: 'Processo externo', error: 'Falha no arranque',
  };
  protected readonly info: Record<ServiceId, { name: string; description: string; mark: string }> = {
    rede: { name: 'Rede Interbancária', description: 'Encaminha transferências e recibos entre os bancos.', mark: '⇄' },
    kwanza: { name: 'Banco Kwanza', description: 'API de contas e transferências do Banco Kwanza.', mark: 'K' },
    horizonte: { name: 'Banco Horizonte', description: 'API de contas e transferências do Banco Horizonte.', mark: 'H' },
    angular: { name: 'Angular', description: 'Interface de clientes dos dois bancos.', mark: '▣' },
  };

  ngOnInit(): void {
    void this.refresh();
    const timer = setInterval(() => void this.refresh(), 1800);
    this.destroy.onDestroy(() => clearInterval(timer));
  }

  protected async refresh(): Promise<void> {
    if (this.refreshing) return;
    this.refreshing = true;
    try {
      this.services.set(await firstValueFrom(this.http.get<Service[]>('/control-api/services')));
      this.connectionError.set('');
    } catch {
      this.connectionError.set('Sem ligação ao controlador local. Reabre o painel pelo ficheiro Abrir-Controlo.vbs.');
    } finally {
      this.refreshing = false;
    }
  }

  protected async run(service: Service, action: 'start' | 'stop'): Promise<void> {
    if (this.pending()) return;
    this.pending.set(service.id);
    this.actionError.set('');
    try {
      await firstValueFrom(this.http.post(
        `/control-api/services/${service.id}/${action}`, null,
        { headers: { 'X-InterBank-Control': 'local' } },
      ));
    } catch (error) {
      this.actionError.set(error instanceof HttpErrorResponse && error.error?.message
        ? error.error.message : 'Não foi possível executar a operação.');
    } finally {
      this.pending.set(null);
      void this.refresh();
    }
  }
}
