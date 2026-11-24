import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RoomApi, ApiError } from '../../main/resources/web/room-api.js';
import { RoomActions } from '../../main/resources/web/room-actions.js';

const viewer = { id: 'account-player', name: 'Alice' };
const session = { authenticated: false, requiresLogin: false, viewer, token: 'guest-csrf' };
const signedIn = { ...session, authenticated: true, token: 'account-csrf' };
const snapshot = { viewer, room: null, pieces: {}, token: 'account-csrf' };

test('sign-in bootstraps a control session and shares its rotated CSRF token with game writes', async context => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push({ path, options });
    if (path === '/api/session') return Response.json(options.method ? signedIn : { ...session, requiresLogin: true, viewer: null });
    return Response.json(snapshot);
  });
  const api = new RoomApi();

  assert.deepEqual(await api.login('alice', 'white knight on e4'), signedIn);
  await api.action('create', { name: 'Encounter Alice' });

  assert.deepEqual(requests.map(request => request.path), ['/api/session', '/api/session', '/games', '/api/state']);
  assert.deepEqual(JSON.parse(requests[1].options.body), { username: 'alice', password: 'white knight on e4', token: 'guest-csrf' });
  assert.deepEqual(JSON.parse(requests[2].options.body), { name: 'Encounter Alice', token: 'account-csrf' });
  assert.ok(requests.every(request => request.options.credentials === 'same-origin'));
  assert.ok(requests.every(request => !request.options.headers?.Authorization));
});

test('account writes use only their public routes and required fields', async context => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push({ path, options });
    return Response.json(session);
  });
  const api = new RoomApi();
  await api.loadAccountSession();
  await api.registerAccount('alice', 'white knight on e4');
  await api.logout();
  await api.deactivateAccount('white knight on e4');

  assert.deepEqual(requests.slice(1).map(request => [request.path, request.options.method]),
    [['/api/accounts', 'POST'], ['/api/session', 'DELETE'], ['/api/accounts/current', 'DELETE']]);
  assert.deepEqual(requests.slice(1).map(request => JSON.parse(request.options.body)), [
    { username: 'alice', password: 'white knight on e4', token: 'guest-csrf' },
    { token: 'guest-csrf' }, { password: 'white knight on e4', token: 'guest-csrf' }
  ]);
});

test('malformed session metadata cannot authorize the frontend', async context => {
  let body;
  context.mock.method(globalThis, 'fetch', async () => Response.json(body));
  const api = new RoomApi();
  for (const invalid of [{}, { ...session, authenticated: 'true' }, { ...session, token: 12 },
    { ...session, viewer: {} }, { ...signedIn, viewer: null }, { ...signedIn, requiresLogin: true }]) {
    body = invalid;
    await assert.rejects(api.loadAccountSession(), /invalid account session/);
  }
  body = { ...session, requiresLogin: true, viewer: null };
  assert.deepEqual(await api.loadAccountSession(), body);
});

test('expired game access preserves the HTTP status and can recover through the control session', async context => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push(path);
    if (path === '/api/state') return Response.json({ message: 'Sign in again.' }, { status: 401 });
    return Response.json(options.method ? signedIn : { ...session, requiresLogin: true, viewer: null });
  });
  const api = new RoomApi();
  await assert.rejects(api.state(), error => error instanceof ApiError && error.status === 401);
  await api.loadAccountSession();
  assert.equal((await api.login('alice', 'white knight on e4')).authenticated, true);
  assert.deepEqual(requests, ['/api/state', '/api/session', '/api/session']);
});

test('a lost registration acknowledgement never automatically repeats the write', async context => {
  let registrations = 0;
  context.mock.method(globalThis, 'fetch', async (path) => {
    if (path === '/api/accounts') {
      registrations++;
      throw new TypeError('Response lost after commit');
    }
    return Response.json({ ...session, requiresLogin: registrations > 0, viewer: null });
  });
  const api = new RoomApi();
  await assert.rejects(api.registerAccount('alice', 'white knight on e4'), /Cannot reach the server/);
  assert.equal((await api.loadAccountSession()).requiresLogin, true);
  assert.equal(registrations, 1);
});

test('a rejected CSRF challenge reloads account control without requesting protected game state', async context => {
  const requests = [];
  let attempts = 0;
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push(path);
    if (options.method && attempts++ === 0) return Response.json({ message: 'Invalid token.' }, { status: 403 });
    return Response.json(session);
  });
  const api = new RoomApi();
  await assert.rejects(api.logout(), error => error.status === 403);
  await api.logout();
  assert.deepEqual(requests, ['/api/session', '/api/session', '/api/session', '/api/session']);
});

test('an account change invalidates in-flight game synchronization and prevents overlapping writes', async () => {
  let finishSynchronization;
  let finishLogin;
  let logoutCalls = 0;
  const current = { ...snapshot, room: { code: 'ABC123' }, pieces: new Map() };
  const actions = new RoomActions({
    state: () => new Promise(resolve => { finishSynchronization = resolve; }),
    login: () => new Promise(resolve => { finishLogin = resolve; }),
    logout: async () => { logoutCalls++; }
  });
  const synchronization = actions.synchronize(current);
  const login = actions.login('alice', 'white knight on e4');
  assert.equal(await actions.logout(), undefined);
  finishLogin(signedIn);
  assert.deepEqual(await login, signedIn);
  finishSynchronization({ ...current, viewer: { id: 'old-guest', name: 'Guest' } });
  assert.equal(await synchronization, undefined);
  assert.equal(logoutCalls, 0);
  await actions.logout();
  assert.equal(logoutCalls, 1);
});
