const find = (selector) => document.querySelector(selector);
const clone = (id) => find(id).content.firstElementChild.cloneNode(true);
const board = find('#board');
const roomForm = find('#room-form');
const profileForm = find('#profile-form');
const promotionDialog = find('#promotion-dialog');
const promotionLetters = { queen: 'Q', rook: 'R', bishop: 'B', knight: 'N' };
let snapshot = { viewer: null, room: null, pieces: new Map() };
let selected = null;
let busy = true;
let savedProfile;
let profileNeedsReload = true;
let roomNameEdited = false;
let accountSession;
let accountBlocked = true;
let accountNeedsReload = true;

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
  find('#save-profile').disabled = value || accountBlocked || profileNeedsReload;
  find('#profile-name').disabled = value || accountBlocked || profileNeedsReload;
  find('#reload-profile').disabled = value || accountBlocked || !snapshot.viewer;
  roomForm.querySelectorAll('button, input').forEach(element => { element.disabled = value || accountBlocked; });
  find('#leave-room').disabled = value || accountBlocked;
  find('#account-form').querySelectorAll('button, input').forEach(element => { element.disabled = value || accountNeedsReload; });
  find('#deactivation-form').querySelectorAll('button, input').forEach(element => {
    element.disabled = value || accountNeedsReload || !accountSession?.authenticated;
  });
  find('#sign-out').disabled = value || accountNeedsReload;
}

function boardLocked() {
  return busy || accountBlocked || gameFinished(snapshot?.room);
}

function gameFinished(room) {
  return Boolean(room?.winner || room?.stalemate);
}

function positionMessage(room) {
  if (room?.winner) return `${room.winner} wins by checkmate.`;
  if (room?.stalemate) return 'Draw by stalemate.';
  return room?.inCheck ? `${room.turn} is in check.` : '';
}

function showPanel(panel) {
  find(`#tab-${panel}`).checked = true;
}

function pieceColor(piece) {
  return piece?.startsWith('White') ? 'White' : 'Black';
}

function needsPromotion(from, to) {
  const pawn = snapshot.pieces.get(from);
  const fileDistance = Math.abs(from.charCodeAt(0) - to.charCodeAt(0));
  if (fileDistance !== (snapshot.pieces.has(to) ? 1 : 0)) return false;
  return (pawn === 'WhitePawn' && from[1] === '7' && to[1] === '8') ||
    (pawn === 'BlackPawn' && from[1] === '2' && to[1] === '1');
}

function choosePromotion() {
  promotionDialog.returnValue = '';
  const choice = new Promise((resolve) => {
    promotionDialog.addEventListener('close', () => resolve(promotionDialog.returnValue), { once: true });
  });
  promotionDialog.showModal();
  return choice;
}

async function submitMove(from, to, perform) {
  const fields = { from, to, code: snapshot.room.code };
  if (needsPromotion(from, to)) {
    const promotion = await choosePromotion();
    if (!promotion || selected !== from) return;
    fields.promotion = promotion;
  }
  await perform('move', fields);
}

function renderPlayer(target, player, color, connected) {
  const element = clone('#player-template');
  const isWhite = color === 'White';
  const name = player?.name || (isWhite ? 'White' : 'Waiting for opponent');
  const isMe = Boolean(player && player.id === snapshot.viewer?.id);
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
  element.querySelector('[data-player-description]').textContent = !player ? 'Not joined yet'
    : connected === false ? 'Disconnected' : 'Friendly game';
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
  board.setAttribute('aria-label', accountBlocked ? 'Saved chess position. Check your session to continue.'
    : finished ? 'Final chess position' : 'Select a piece and its destination');
  find('#board-hint').textContent = accountBlocked ? 'Sign in or check your session to continue.' : finished ? 'Game over.'
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
      const promotion = move?.promotion ? `=${promotionLetters[move.promotion]}` : '';
      cell.textContent = move ? `${move.from}–${move.to}${promotion}` : '···';
      cell.classList.toggle('latest', index + offset === moves.length - 1);
    }
    rows.append(row);
  }
  find('#history-rows').replaceChildren(rows);
  find('#empty-history').hidden = moves.length > 0;
}

function renderPlayers() {
  const room = snapshot.room;
  renderPlayer('#top-player', room?.black, 'Black', snapshot.presence?.blackConnected);
  renderPlayer('#bottom-player', room?.white || snapshot.viewer, 'White', snapshot.presence?.whiteConnected);
}

function render() {
  const room = snapshot.room;
  renderPlayers();
  renderBoard();
  renderHistory();
  find('#room-subtitle').textContent = room ? `Room ${room.code} · Friendly game` : 'One board. Endless possibilities.';
  find('#game-status').textContent = positionMessage(room) ||
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
  if (!roomNameEdited) {
    const name = savedProfile?.name || snapshot.viewer?.name || 'Guest';
    find('#player-name').value = name === 'Guest' ? '' : name;
  }
}

function showProfileStatus(message, error = false) {
  const status = find('#profile-status');
  status.textContent = message;
  status.classList.toggle('error', error);
}

function showAccountStatus(message, error = false) {
  const status = find('#account-status');
  status.textContent = message;
  status.classList.toggle('error', error);
}

function applyAccountSession(session) {
  accountSession = session;
  accountNeedsReload = false;
  accountBlocked = session.requiresLogin;
  find('#account-form').hidden = session.authenticated;
  find('#account-form button[value=register]').hidden = session.requiresLogin;
  find('#account-exit').hidden = !session.authenticated && !session.requiresLogin;
  find('#deactivation-area').hidden = !session.authenticated;
  find('#sign-out').textContent = session.requiresLogin ? 'Continue as a new guest' : 'Sign out';
  showAccountStatus(session.message || (session.requiresLogin ? 'Sign in to recover access to your saved games.'
    : session.authenticated ? 'Signed in. Your player identity works across browsers.'
      : 'Playing as a guest. Create an account to keep your player identity across browsers.'));
  if (session.requiresLogin) {
    selected = null;
    if (promotionDialog.open) promotionDialog.close();
    showProfileStatus('Sign in before loading or saving your profile.', true);
    showPanel('account');
  }
}

async function loadCurrentPlayer(actions) {
  const next = await actions.load();
  if (snapshot.viewer?.id !== next.viewer.id) {
    savedProfile = undefined;
    profileNeedsReload = true;
    roomNameEdited = false;
    find('#profile-name').value = '';
  }
  snapshot = next;
  selected = null;
  render();
  try { await loadPlayerProfile(actions); }
  catch (error) {
    profileNeedsReload = true;
    if (error.status === 401) throw error;
    showProfileStatus(`${error.message} Load the saved name before trying to save.`, true);
  }
}

async function reloadAccountSession(actions) {
  if (busy) return;
  setBusy(true);
  try {
    applyAccountSession(await actions.loadAccountSession());
    if (!accountBlocked) await loadCurrentPlayer(actions);
    notify(accountBlocked ? 'Your game is saved. Sign in to continue.' : 'Session checked.');
    render();
  } catch (error) {
    accountNeedsReload = true;
    accountBlocked = true;
    showAccountStatus(`${error.message} Check the session before continuing.`, true);
  } finally { setBusy(false); }
}

async function handleAuthorizationFailure(actions, error) {
  if (error.status !== 401) return false;
  accountBlocked = true;
  selected = null;
  if (promotionDialog.open) promotionDialog.close();
  try {
    applyAccountSession(await actions.loadAccountSession());
    accountBlocked = true;
    accountNeedsReload = !accountSession.requiresLogin;
    if (accountNeedsReload) showAccountStatus('Your session changed. Check the session before continuing.', true);
  } catch {
    accountNeedsReload = true;
    showAccountStatus('Your session could not be confirmed. Check the session before continuing.', true);
  }
  showPanel('account');
  render();
  setBusy(busy);
  return true;
}

async function performAccountOperation(actions, operation) {
  if (busy || accountNeedsReload) return;
  setBusy(true);
  try {
    const session = await operation();
    applyAccountSession(session);
    if (!accountBlocked) await loadCurrentPlayer(actions);
    notify(session.message || 'Session updated.');
  } catch (error) {
    try {
      applyAccountSession(await actions.loadAccountSession());
      if (!accountBlocked) await loadCurrentPlayer(actions);
      showAccountStatus(`${error.message} ${accountBlocked ? 'Try signing in before creating another account.' : 'Your session has been checked.'}`, true);
    } catch {
      accountNeedsReload = true;
      accountBlocked = true;
      showAccountStatus(`${error.message} The session could not be confirmed. Check it before retrying.`, true);
    }
    notify(error.message, true);
  } finally {
    find('#account-password').value = '';
    find('#deactivation-password').value = '';
    find('#confirm-deactivation').checked = false;
    render();
    setBusy(false);
  }
}

function applyPlayerProfile(profile, replaceDraft) {
  savedProfile = profile;
  profileNeedsReload = false;
  if (replaceDraft) find('#profile-name').value = profile.name;
  snapshot = { ...snapshot, viewer: profile };
  render();
}

async function loadPlayerProfile(actions) {
  const response = await actions.loadPlayerProfile();
  if (!response) return;
  applyPlayerProfile(response.profile, true);
  showProfileStatus(`Saved as ${response.profile.name}. Existing games keep their recorded names.`);
}

async function reloadPlayerProfile(actions) {
  if (busy || accountBlocked) return;
  setBusy(true);
  try {
    await loadPlayerProfile(actions);
  } catch (error) {
    if (await handleAuthorizationFailure(actions, error)) return;
    profileNeedsReload = true;
    showProfileStatus(`${error.message} Load the saved name before trying to save.`, true);
  } finally { setBusy(false); }
}

async function savePlayerProfile(actions) {
  if (busy || accountBlocked || profileNeedsReload) return;
  const name = find('#profile-name').value;
  setBusy(true);
  showProfileStatus('Saving your profile…');
  try {
    const response = await actions.updatePlayerDisplayName(name);
    if (!response) return;
    applyPlayerProfile(response.profile, true);
    showProfileStatus(`Saved as ${response.profile.name}. Existing games keep their recorded names.`);
  } catch (error) {
    if (await handleAuthorizationFailure(actions, error)) return;
    profileNeedsReload = true;
    try {
      const response = await actions.loadPlayerProfile();
      if (!response) return;
      const confirmed = response.profile.name === name.trim();
      applyPlayerProfile(response.profile, confirmed);
      showProfileStatus(confirmed ? `Saved as ${response.profile.name}. The saved name was confirmed after a connection problem.`
        : `${error.message} Saved name: ${response.profile.name}.`, !confirmed);
    } catch (confirmationError) {
      if (await handleAuthorizationFailure(actions, confirmationError)) return;
      showProfileStatus(`${error.message} The saved name could not be confirmed. Load it before trying to save again.`, true);
    }
  } finally { setBusy(false); }
}

async function performAction(actions, action, fields) {
  if (busy || accountBlocked) return;
  setBusy(true);
  try {
    const next = await actions.perform(action, fields);
    if (!next) return;
    snapshot = next;
    selected = null;
    render();
    if (action === 'create' || action === 'leave') showPanel('room');
    if (action === 'join') showPanel('moves');
    notify(positionMessage(snapshot.room) || actionMessage(action, fields));
  } catch (error) {
    notify(error.message, true);
    if (await handleAuthorizationFailure(actions, error)) return;
    const recovered = await actions.recoverAfter(action);
    if (recovered) {
      snapshot = recovered;
      selected = null;
      render();
    }
  } finally { setBusy(false); }
}

function actionMessage(action, fields) {
  if (action === 'create') return `Room created. Share code ${snapshot.room.code} with your opponent.`;
  if (action === 'move') return `Move recorded: ${fields.from} → ${fields.to}.`;
  return snapshot.message;
}

async function syncState(actions) {
  if (busy || accountBlocked) return;
  try {
    const result = await actions.synchronize(snapshot);
    if (!result) return;
    if (result.recovered) {
      notify('Connection restored.');
      return;
    }
    const next = result.snapshot;
    if (result.presenceOnly) {
      const previous = snapshot;
      snapshot = next;
      renderPlayers();
      const connected = opponentConnected(next);
      if (connected !== opponentConnected(previous)) {
        notify(connected ? 'Your opponent reconnected.' : 'Your opponent disconnected. The game is saved.');
      }
      return;
    }
    const opponentJoined = !snapshot.room.black && next.room?.black;
    snapshot = next;
    selected = null;
    if (promotionDialog.open) promotionDialog.close();
    render();
    notify(positionMessage(next.room) || (!next.room ? 'Your session has changed. Create or join a room.'
      : opponentJoined ? 'Your opponent joined. White to move.' : `Game updated. ${next.room.turn} to move.`));
  } catch (error) {
    await handleAuthorizationFailure(actions, error);
    notify(error.message, true);
  }
}

function opponentConnected(state) {
  return state.room?.white.id === state.viewer.id ? state.presence?.blackConnected : state.presence?.whiteConnected;
}

function handleBoardClick(event, perform) {
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
  else if (selected) { submitMove(selected, coordinate, perform); return; }
  else { notify('Select one of your pieces first.', true); return; }
  showPanel('moves');
  notify('');
  renderBoard();
  board.querySelector(`[data-square="${coordinate}"]`).focus();
}

async function initialize(actions) {
  setBusy(true);
  try {
    applyAccountSession(await actions.loadAccountSession());
    if (!accountBlocked) await loadCurrentPlayer(actions);
    render();
    notify(accountBlocked ? 'Sign in to continue. Recorded games are saved.' : '');
  } catch (error) {
    accountBlocked = true;
    accountNeedsReload = true;
    showAccountStatus(`${error.message} Check the session to reconnect.`, true);
    showPanel('account');
    render();
    notify(error.message, true);
    showProfileStatus('Refresh the game to reconnect, then load your saved name.', true);
  }
  finally { setBusy(false); }
}

export function startApp(actions) {
  const perform = (action, fields) => performAction(actions, action, fields);
  const synchronize = () => syncState(actions);

  board.addEventListener('click', (event) => handleBoardClick(event, perform));
  roomForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(roomForm));
    perform(event.submitter?.value || 'create', fields);
  });
  find('#player-name').addEventListener('input', () => { roomNameEdited = true; });
  profileForm.addEventListener('submit', (event) => {
    event.preventDefault();
    savePlayerProfile(actions);
  });
  find('#reload-profile').addEventListener('click', () => reloadPlayerProfile(actions));
  find('#refresh-game').addEventListener('click', () => accountBlocked ? reloadAccountSession(actions) : perform('refresh'));
  find('#leave-room').addEventListener('click', () => perform('leave'));
  find('#account-form').addEventListener('submit', event => {
    event.preventDefault();
    const username = find('#account-username').value;
    const password = find('#account-password').value;
    performAccountOperation(actions, () => event.submitter?.value === 'register'
      ? actions.registerAccount(username, password) : actions.login(username, password));
  });
  find('#sign-out').addEventListener('click', () => performAccountOperation(actions, () => actions.logout()));
  find('#deactivation-form').addEventListener('submit', event => {
    event.preventDefault();
    const password = find('#deactivation-password').value;
    if (find('#confirm-deactivation').checked)
      performAccountOperation(actions, () => actions.deactivateAccount(password));
  });
  find('#reload-account').addEventListener('click', () => reloadAccountSession(actions));
  find('.brand').addEventListener('click', (event) => { event.preventDefault(); showPanel('moves'); });
  find('.nav-item.active').addEventListener('click', (event) => { event.preventDefault(); showPanel('moves'); });

  initialize(actions);
  setInterval(synchronize, 2000);
  window.addEventListener('focus', synchronize);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) synchronize(); });
}
