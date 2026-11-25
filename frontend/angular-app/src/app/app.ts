import { Component, inject } from '@angular/core';

import { BankDashboard } from './pages/bank-dashboard';
import { Login } from './pages/login';
import { ServiceControl } from './pages/service-control';
import { Session } from './services/session';

@Component({
  selector: 'app-root',
  imports: [BankDashboard, Login, ServiceControl],
  template: `
    @if (control) { <app-service-control /> }
    @else if (session.customer()) { <app-bank-dashboard /> }
    @else { <app-login /> }
  `,
})
export class App {
  protected readonly session = inject(Session);
  protected readonly control = location.pathname === '/controlo';
}
