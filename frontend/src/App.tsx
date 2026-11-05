import { Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth';
import { Shell } from './layout';
import { Admin } from './pages/Admin';
import { Login, Register } from './pages/Auth';
import { Chats } from './pages/Chats';
import { Comments, EventPage, Going } from './pages/Event';
import { InviteStatus, Invites } from './pages/Invites';
import { Lists } from './pages/Lists';
import { NotFound } from './pages/NotFound';
import { Notifications } from './pages/Notifications';
import { Person } from './pages/Person';
import { EditProfile, Profile, Taste } from './pages/Profile';
import { Search } from './pages/Search';
import { Social } from './pages/Social';
import { Swipe } from './pages/Swipe';

function Private({ children }: { children: JSX.Element }) {
  return <RequireAuth>{children}</RequireAuth>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route element={<Shell />}>
        <Route index element={<Swipe />} />
        <Route path="search" element={<Search />} />
        <Route path="events/:id" element={<EventPage />} />
        <Route path="events/:id/comments" element={<Comments />} />
        <Route path="events/:id/going" element={<Private><Going /></Private>} />
        <Route path="social" element={<Private><Social /></Private>} />
        <Route path="list" element={<Private><Lists /></Private>} />
        <Route path="users/:id" element={<Private><Person /></Private>} />
        <Route path="invites" element={<Private><Invites /></Private>} />
        <Route path="invites/:id" element={<Private><InviteStatus /></Private>} />
        <Route path="chats" element={<Private><Chats /></Private>} />
        <Route path="chats/:id" element={<Private><Chats /></Private>} />
        <Route path="notifications" element={<Private><Notifications /></Private>} />
        <Route path="profile" element={<Private><Profile /></Private>} />
        <Route path="profile/edit" element={<Private><EditProfile /></Private>} />
        <Route path="profile/taste" element={<Private><Taste /></Private>} />
        <Route path="admin" element={<RequireAuth moderator><Admin /></RequireAuth>} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
