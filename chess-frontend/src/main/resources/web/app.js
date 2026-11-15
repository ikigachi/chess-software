import { RoomApi } from './room-api.js';

const api = new RoomApi();
const find = (selector) => document.querySelector(selector);
const clone = (id) => find(id).content.firstElementChild.cloneNode(true);
const board = find('#board');
const roomForm = find('#room-form');
let snapshot;
let selected = null;
let busy = true;
let stateVersion = 0;
let syncing = false;
let syncFailed = false;

function notify(message, error = false) {
  const notice = find('#notice');
  notice.textContent = message;
  notice.classList.toggle('error', error);
}

function setBusy(value) {
  busy = value;
  board.setAttribute('aria-busy', String(value));
  document.querySelectorAll('button').forEach((button) => {
    button.disabled = button.hasAttribute('data-square') ? boardLocked() : value;
  });
}

function boardLocked() {
  return busy || gameFinished(snapshot?.room);
}

function gameFinished(room) {
  return Boolean(room?.winner || room?.stalemate);
}

function resultMessage(room) {
  if (room?.winner) return `${room.winner} wins by checkmate.`;
  return room?.stalemate ? 'Draw by stalemate.' : '';
}

function showPanel(panel) {
  find(`#tab-${panel}`).checked = true;
}

function pieceColor(piece) {
  return piece?.startsWith('White') ? 'White' : 'Black';
}

function renderPlayer(target, player, color) {
  const element = clone('#player-template');
  const isWhite = color === 'White';
  const name = player?.name || (isWhite ? 'White' : 'Waiting for opponent');
  const isMe = player?.id === snapshot.viewer.id;
  const room = snapshot.room;
  const isTurn = Boolean(room?.black && !gameFinished(room) && room.turn === color);
  element.classList.add(isWhite ? 'bottom' : 'top');
  const avatar = element.querySelector('.avatar');
  avatar.textContent = player ? [...name][0].toUpperCase() : '+';
  avatar.classList.toggle('dark', !isWhite && Boolean(player));
  avatar.classList.toggle('empty', !player);
  element.querySelector('[data-player-name]').textContent = name;
  element.querySelector('.you-tag').hidden = !isMe;
  element.querySelector('[data-player-color]').textContent = `${color} pieces`;
  element.querySelector('[data-player-description]').textContent = player ? 'Friendly game' : 'Not joined yet';
  const status = element.querySelector('.player-status');
  status.textContent = isTurn ? 'To move' : color;
  status.classList.toggle('turn', isTurn);
  find(target).replaceChildren(element);
}

function renderBoard() {
  const room = snapshot.room;
  const last = room?.moves.at(-1);
  const finished = gameFinished(room);
  const locked = boardLocked();
  const squares = document.createDocumentFragment();
  for (let rank = 8; rank >= 1; rank--) {
    for (let file = 0; file < 8; file++) {
      const coordinate = `${String.fromCharCode(97 + file)}${rank}`;
      const piece = snapshot.pieces.get(coordinate);
      const button = clone('#square-template');
      button.value = coordinate;
      button.dataset.square = coordinate;
      button.classList.toggle('dark', (file + 1 + rank) % 2 === 0);
      button.classList.toggle('selected', coordinate === selected);
      button.classList.toggle('last-move', last?.from === coordinate || last?.to === coordinate);
      button.setAttribute('aria-pressed', String(coordinate === selected));
      const type = piece?.slice(5).toLowerCase();
      button.setAttribute('aria-label', `${coordinate}, ${piece ? `${type} (${pieceColor(piece)})` : 'empty'}`);
      const image = button.querySelector('img');
      if (piece) {
        image.src = `/assets/pieces/${pieceColor(piece).toLowerCase()}-${type}.svg`;
        image.classList.add(pieceColor(piece) === 'White' ? 'ivory' : 'bronze');
      } else image.remove();
      button.disabled = locked;
      squares.append(button);
    }
  }
  board.replaceChildren(squares);
  board.setAttribute('aria-label', finished ? 'Final chess position' : 'Select a piece and its destination');
  find('#board-hint').textContent = finished ? 'Game over.'
    : selected ? `Selected piece: ${selected}. Choose a destination square.`
      : !room ? 'Your next great move starts here.'
        : room.black ? 'Select a piece, then its destination.' : 'Share your room code to get started.';
}

function renderHistory() {
  const moves = snapshot.room?.moves || [];
  const rows = document.createDocumentFragment();
  for (let index = 0; index < moves.length; index += 2) {
    const row = clone('#move-template');
    row.querySelector('[data-move-number]').textContent = `${index / 2 + 1}.`;
    for (const [offset, selector] of [[0, '[data-white-move]'], [1, '[data-black-move]']]) {
      const cell = row.querySelector(selector);
      const move = moves[index + offset];
      cell.textContent = move ? `${move.from}–${move.to}` : '···';
      cell.classList.toggle('latest', index + offset === moves.length - 1);
    }
    rows.append(row);
  }
  find('#history-rows').replaceChildren(rows);
  find('#empty-history').hidden = moves.length > 0;
}

function render() {
  const room = snapshot.room;
  renderPlayer('#top-player', room?.black, 'Black');
  renderPlayer('#bottom-player', room?.white || snapshot.viewer, 'White');
  renderBoard();
  renderHistory();
  find('#room-subtitle').textContent = room ? `Room ${room.code} · Friendly game` : 'One board. Endless possibilities.';
  find('#game-status').textContent = resultMessage(room) ||
    (!room ? 'Board preview' : room.black ? `Turn: ${room.turn}` : 'Waiting for the second player');
  find('#insight').textContent = gameFinished(room) ? 'Review the moves and final position.'
    : !room ? 'Chess begins with an invitation. Create a room and share a moment of calm.'
      : room.black ? 'Consider your next move. There is no clock: time is on your side.'
        : 'White is ready. Share your code and wait for your opponent to arrive.';
  find('#room-info').hidden = !room;
  find('#leave-area').hidden = !room;
  find('#room-code-display').textContent = room?.code || '';
  find('#room-description').textContent = room?.black ? 'Two players, one game. Moves update automatically.'
    : 'Waiting for your opponent. Share this code.';
  if (!find('#player-name').value && snapshot.viewer.name !== 'Guest') find('#player-name').value = snapshot.viewer.name;
}

async function perform(action, fields = {}) {
  if (busy) return;
  stateVersion++;
  syncFailed = false;
  setBusy(true);
  try {
    snapshot = await api.action(action, fields);
    selected = null;
    render();
    if (action === 'create' || action === 'leave') showPanel('room');
    if (action === 'join') showPanel('moves');
    notify(resultMessage(snapshot.room) || snapshot.message);
  } catch (error) {
    notify(error.message, true);
    if (action === 'move') {
      try {
        snapshot = await api.state();
        selected = null;
        render();
      } catch {}
    }
  } finally { setBusy(false); }
}

function stateKey(state) {
  return JSON.stringify([state.viewer, state.room, [...state.pieces].sort()]);
}

async function syncState() {
  if (busy || syncing || !snapshot?.room) return;
  const version = stateVersion;
  syncing = true;
  try {
    const next = await api.state();
    if (busy || version !== stateVersion) return;
    const recovered = syncFailed;
    syncFailed = false;
    if (stateKey(next) === stateKey(snapshot)) {
      if (recovered) notify('Connection restored.');
      return;
    }
    const opponentJoined = !snapshot.room.black && next.room?.black;
    snapshot = next;
    selected = null;
    render();
    notify(resultMessage(next.room) || (!next.room ? 'Your session has changed. Create or join a room.'
      : opponentJoined ? 'Your opponent joined. White to move.' : `Game updated. ${next.room.turn} to move.`));
  } catch (error) {
    if (!busy && version === stateVersion) {
      syncFailed = true;
      notify(error.message, true);
    }
  } finally { syncing = false; }
}

board.addEventListener('click', (event) => {
  const button = event.target.closest('[data-square]');
  if (!button || boardLocked() || !snapshot) return;
  const room = snapshot.room;
  if (!room) { showPanel('room'); notify('Create or join a room to get started.', true); return; }
  if (!room.black) { notify('Wait for the second player to join.', true); return; }
  const color = room.white.id === snapshot.viewer.id ? 'White' : 'Black';
  if (room.turn !== color) { notify('It is your opponent’s turn.', true); return; }
  const coordinate = button.dataset.square;
  const piece = snapshot.pieces.get(coordinate);
  if (selected === coordinate) selected = null;
  else if (piece && pieceColor(piece) === color) selected = coordinate;
  else if (selected) { perform('move', { from: selected, to: coordinate }); return; }
  else { notify('Select one of your pieces first.', true); return; }
  showPanel('moves');
  notify('');
  renderBoard();
  board.querySelector(`[data-square="${coordinate}"]`).focus();
});

roomForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const fields = Object.fromEntries(new FormData(roomForm));
  perform(event.submitter?.value || 'create', fields);
});
find('#refresh-game').addEventListener('click', () => perform('refresh'));
find('#leave-room').addEventListener('click', () => perform('leave'));
find('.brand').addEventListener('click', (event) => { event.preventDefault(); showPanel('moves'); });
find('.nav-item.active').addEventListener('click', (event) => { event.preventDefault(); showPanel('moves'); });

async function initialize() {
  setBusy(true);
  try {
    snapshot = await api.state();
    render();
    notify('');
  } catch (error) { notify(error.message, true); }
  finally { setBusy(false); }
}

initialize();
setInterval(syncState, 2000);
window.addEventListener('focus', syncState);
document.addEventListener('visibilitychange', () => { if (!document.hidden) syncState(); });
