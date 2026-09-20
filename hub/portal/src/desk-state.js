export const deskState = {
  unread: {},
};

export function unreadTotal(unread = deskState.unread) {
  return Object.values(unread || {}).reduce((sum, n) => sum + (Number(n) || 0), 0);
}

export function bumpUnread(peer) {
  if (!peer) return;
  deskState.unread[peer] = (deskState.unread[peer] || 0) + 1;
}

export function clearUnread(peer) {
  if (!peer) return;
  delete deskState.unread[peer];
}

export function paintUnreadBadge() {
  const el = document.getElementById("unread-badge");
  if (!el) return;
  const total = unreadTotal();
  el.hidden = total === 0;
  el.textContent = String(total);
}
