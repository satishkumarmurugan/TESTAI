import { Routes } from '@angular/router';
import { ChatComponent } from './pages/chat/chat';
import { AgentsComponent } from './pages/agents/agents';
import { OnboardComponent } from './pages/onboard/onboard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'chat' },
  { path: 'chat', component: ChatComponent },
  { path: 'agents', component: AgentsComponent },
  { path: 'agents/new', component: OnboardComponent },
  { path: '**', redirectTo: 'chat' }
];
