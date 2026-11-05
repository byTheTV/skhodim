export type EventSummary = {
  eventId: number;
  title: string;
  description: string | null;
  coverUrl: string | null;
  isFree: boolean | null;
  priceFrom: number | null;
  priceText: string | null;
  ageLimit: number | null;
  ticketUrl: string | null;
  placeName: string | null;
  address: string | null;
  metro: string | null;
  latitude: number | null;
  longitude: number | null;
  sessionId: number | null;
  startsAt: string | null;
  endsAt: string | null;
  isUpcoming: boolean;
  categories: string[];
  goingCount: number;
  commentCount: number;
};

export type EventSession = { id: number; startsAt: string; endsAt: string | null; cancelledAt: string | null };
export type Category = { id: number; slug: string; name: string };
export type EventUserState = {
  reaction: 'LIKE' | 'SKIP' | null;
  goingSessionId: number | null;
  reviewSessionId: number | null;
  hasReview: boolean;
};
export type EventDetails = {
  summary: EventSummary;
  sessions: EventSession[];
  categories: Category[];
  tags: { id: number; name: string }[];
  sourceUrl: string;
  state: EventUserState;
};
export type ListEntry = { kind: string; sessionId: number | null; markedAt: string; event: EventSummary };
export type MyLists = { going: ListEntry[]; liked: ListEntry[]; visited: ListEntry[] };
export type Person = {
  userId: number;
  nickname: string;
  displayName: string;
  avatarKey: string | null;
  bio: string | null;
  similarity: number;
  sharedCount: number;
  sharedEventIds: number[];
  sharedTitles: string[];
  interests: string[];
};
export type GoingPerson = {
  userId: number;
  nickname: string;
  displayName: string;
  avatarKey: string | null;
  sessionId: number;
  sameSession: boolean;
  interests: string[];
  recentLike: string | null;
  requestId: number | null;
  requestStatus: string | null;
  requestOutgoing: boolean | null;
  conversationId: number | null;
};
export type ProfilePage = {
  profile: {
    userId: number;
    nickname: string;
    displayName: string;
    avatarKey: string | null;
    metro: string | null;
    bio: string | null;
    interests: string[];
    similarity: number | null;
  };
  recentLikes: EventSummary[];
  sharedLikes: EventSummary[];
  sharedPlans: { sessionId: number; event: EventSummary }[];
};
export type RequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';
export type RequestView = {
  requestId: number;
  outgoing: boolean;
  status: RequestStatus;
  peerId: number;
  peerNickname: string;
  peerDisplayName: string;
  peerAvatarKey: string | null;
  eventId: number;
  eventTitle: string;
  coverUrl: string | null;
  placeName: string | null;
  sessionId: number;
  startsAt: string;
  createdAt: string;
  resolvedAt: string | null;
  conversationId: number | null;
};
export type ChatView = {
  conversationId: number;
  peerId: number;
  peerNickname: string;
  peerDisplayName: string;
  peerAvatarKey: string | null;
  eventId: number;
  eventTitle: string;
  coverUrl: string | null;
  placeName: string | null;
  startsAt: string;
  lastBody: string | null;
  lastAuthorId: number | null;
  lastAt: string;
  unreadCount: number;
};
export type Message = { id: number; authorId: number; body: string; createdAt: string };
export type Feedback = {
  id: number;
  eventId: number;
  eventTitle: string | null;
  sessionId: number | null;
  authorId: number;
  nickname: string;
  displayName: string;
  avatarKey: string | null;
  body: string;
  rating: number | null;
  createdAt: string;
};
export type NotificationView = {
  id: number;
  kind: 'REQUEST' | 'ACCEPTED' | 'REJECTED' | 'MESSAGE';
  createdAt: string;
  readAt: string | null;
  requestId: number | null;
  conversationId: number | null;
  actorId: number | null;
  actorName: string | null;
  eventId: number | null;
  eventTitle: string | null;
  preview: string | null;
};
export type TasteSlice = { kind: 'category' | 'metro' | 'place'; itemName: string; weight: number };
export type Badges = { pendingInvites: number; unreadChats: number; unreadNotifications: number };
export type MyProfile = {
  userId: number;
  email: string;
  role: 'USER' | 'MODERATOR';
  profile: {
    userId: number;
    nickname: string;
    displayName: string | null;
    avatarKey: string | null;
    metro: string | null;
    bio: string | null;
    notificationsEnabled: boolean;
  };
  interests: number[];
  interestCategories: Category[];
};
export type ModerationItem = {
  id: number;
  authorId: number;
  authorName: string;
  targetType: 'event' | 'user' | 'feedback';
  targetId: number;
  targetLabel: string | null;
  targetEventId: number | null;
  targetActive: boolean | null;
  reason: string;
  createdAt: string;
};
export type ImportRun = {
  id: number;
  sourceCode: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  processedCount: number;
  errorCount: number;
  lastError: string | null;
};
export type ImportResult = { runId: number; status: string; processed: number; errors: number; origin: string };
export type Metro = { metro: string; eventCount: number };
export type AuthResponse = { token: string; userId: number; email: string; role: 'USER' | 'MODERATOR' };

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const TOKEN_KEY = 'skhodim-token';

function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

let token: string | null = readToken();
let onUnauthorized: () => void = () => {};

export const session = {
  get token() {
    return token;
  },
  set(value: string | null) {
    token = value;
    try {
      if (value) localStorage.setItem(TOKEN_KEY, value);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      // Приватный режим: токен живёт только до перезагрузки.
    }
  },
  onExpired(handler: () => void) {
    onUnauthorized = handler;
  },
};

async function request<T>(method: string, path: string, body?: unknown, raw = false): Promise<T> {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Нет связи с сервером. Проверьте подключение.');
  }
  if (!response.ok) {
    let message = 'Не получилось выполнить действие. Попробуйте ещё раз.';
    try {
      const data = await response.json();
      if (typeof data.error === 'string') message = data.error;
    } catch {
      // Ответ без JSON: остаётся общий текст.
    }
    if (response.status === 401 && token) onUnauthorized();
    throw new ApiError(response.status, message);
  }
  if (raw) return response as unknown as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const get = <T,>(path: string) => request<T>('GET', path);
const post = <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {});
const put = <T,>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {});
const del = <T,>(path: string) => request<T>('DELETE', path);

export type EventQuery = {
  query?: string;
  categoryIds?: number[];
  from?: string;
  to?: string;
  isFree?: boolean;
  metro?: string;
  limit?: number;
  offset?: number;
  forYou?: boolean;
};

function queryString(q: EventQuery): string {
  const p = new URLSearchParams();
  if (q.query) p.set('query', q.query);
  if (q.categoryIds?.length) p.set('categoryIds', q.categoryIds.join(','));
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  if (q.isFree) p.set('isFree', 'true');
  if (q.metro) p.set('metro', q.metro);
  if (q.limit) p.set('limit', String(q.limit));
  if (q.offset) p.set('offset', String(q.offset));
  if (q.forYou) p.set('forYou', 'true');
  const s = p.toString();
  return s ? `?${s}` : '';
}

export const api = {
  login: (email: string, password: string) => post<AuthResponse>('/auth/login', { email, password }),
  register: (data: { email: string; password: string; nickname: string; displayName: string; categoryIds: number[] }) =>
    post<AuthResponse>('/auth/register', data),
  profile: () => get<MyProfile>('/profile'),
  updateProfile: (data: {
    nickname: string;
    displayName: string | null;
    avatarKey: string | null;
    metro: string | null;
    bio: string | null;
    categoryIds?: number[];
    notificationsEnabled?: boolean;
  }) => put<MyProfile>('/profile', data),
  categories: () => get<Category[]>('/categories'),
  metros: () => get<Metro[]>('/metros'),
  events: (q: EventQuery) => get<EventSummary[]>(`/events${queryString(q)}`),
  event: (id: number) => get<EventDetails>(`/events/${id}`),
  swipe: (id: number, kind: 'LIKE' | 'SKIP') =>
    post(`/events/${id}/swipe`, { kind, isPublic: kind === 'LIKE' }),
  unreact: (id: number) => del(`/favorites/${id}`),
  goingOn: (id: number, sessionId?: number | null) => post(`/events/${id}/going`, sessionId ? { sessionId } : {}),
  goingOff: (id: number, sessionId?: number | null) =>
    del(`/events/${id}/going${sessionId ? `?sessionId=${sessionId}` : ''}`),
  goingPeople: (id: number) => get<GoingPerson[]>(`/events/${id}/going`),
  comments: (id: number) => get<Feedback[]>(`/events/${id}/comments`),
  reviews: (id: number) => get<Feedback[]>(`/events/${id}/reviews`),
  addComment: (id: number, body: string) => post(`/events/${id}/comments`, { body }),
  deleteComment: (eventId: number, id: number) => del(`/events/${eventId}/comments/${id}`),
  addReview: (id: number, sessionId: number, body: string, rating: number) =>
    post(`/events/${id}/reviews`, { sessionId, body, rating }),
  shareCard: async (id: number): Promise<Blob> => {
    const response = await request<Response>('GET', `/events/${id}/share-card`, undefined, true);
    return response.blob();
  },
  lists: () => get<MyLists>('/lists'),
  suggested: (limit = 10) => get<Person[]>(`/people/suggested?limit=${limit}`),
  user: (id: number) => get<ProfilePage>(`/users/${id}`),
  feed: (limit = 20) => get<Feedback[]>(`/feed/comments?limit=${limit}`),
  taste: () => get<TasteSlice[]>('/me/taste'),
  badges: () => get<Badges>('/me/badges'),
  requests: () => get<{ incoming: RequestView[]; outgoing: RequestView[] }>('/companion-requests'),
  request: (id: number) => get<RequestView>(`/companion-requests/${id}`),
  sendRequest: (receiverId: number, sessionId: number) =>
    post<{ requestId: number }>('/companion-requests', { receiverId, sessionId }),
  resolveRequest: (id: number, accept: boolean) =>
    put<{ accepted: boolean; conversationId?: number }>(`/companion-requests/${id}`, { accept }),
  cancelRequest: (id: number) => post(`/companion-requests/${id}/cancel`),
  chats: () => get<ChatView[]>('/chats'),
  chat: (id: number) => get<ChatView>(`/chats/${id}`),
  messages: (id: number) => get<Message[]>(`/chats/${id}/messages?limit=200`),
  sendMessage: (id: number, body: string) => post(`/chats/${id}/messages`, { body }),
  readChat: (id: number) => post(`/chats/${id}/read`),
  notifications: () => get<NotificationView[]>('/notifications'),
  readNotification: (id: number) => put(`/notifications/${id}`),
  readAllNotifications: () => post('/notifications/read-all'),
  report: (targetType: 'event' | 'user' | 'feedback', targetId: number, reason: string) =>
    post('/reports', { targetType, targetId, reason }),
  moderation: () => get<ModerationItem[]>('/admin/moderation'),
  moderate: (reportId: number, action: string) => put('/admin/moderation', { reportId, action }),
  sync: () => post<ImportResult>('/admin/sync'),
  uploadSnapshot: (json: string) => request<ImportResult>('POST', '/admin/sync/snapshot', json),
  imports: () => get<ImportRun[]>('/admin/imports'),
};
