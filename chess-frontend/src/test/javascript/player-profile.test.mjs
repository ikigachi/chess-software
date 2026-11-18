import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RoomApi } from '../../main/resources/web/room-api.js';
import { RoomActions } from '../../main/resources/web/room-actions.js';

const profile = { id: 'white', name: 'Alice' };
const room = { code: 'ABC123', white: profile, black: { id: 'black', name: 'Bob' }, moves: [] };
const snapshot = { viewer: profile, room, pieces: { e2: 'WhitePawn' }, token: 'session-token' };

test('loads and updates profiles without treating them as board snapshots or sending a credential', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push({ path, options });
    return Response.json(path === '/api/state' ? snapshot : { profile, token: 'session-token' });
  });
  const api = new RoomApi();

  assert.deepEqual((await api.state()).pieces, new Map([['e2', 'WhitePawn']]));
  assert.deepEqual((await api.loadPlayerProfile()).profile, profile);
  assert.deepEqual((await api.updatePlayerDisplayName('Alice')).profile, profile);

  assert.deepEqual(requests.map(request => request.path), ['/api/state', '/api/profile', '/api/profile']);
  assert.equal(requests[1].options.method, undefined);
  assert.equal(requests[2].options.method, 'PUT');
  assert.equal(requests[2].options.credentials, 'same-origin');
  assert.deepEqual(JSON.parse(requests[2].options.body), { name: 'Alice', token: 'session-token' });
});

test('creating a room keeps its name separate from saving a profile', async (context) => {
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    requests.push({ path, options });
    return Response.json(snapshot);
  });

  await new RoomApi().action('create', { name: 'Encounter name' });

  assert.deepEqual(requests.map(request => request.path), ['/api/state', '/games', '/api/state']);
  assert.deepEqual(JSON.parse(requests[1].options.body), { name: 'Encounter name', token: 'session-token' });
});

test('a lost update response is not retried and the saved profile can be queried', async (context) => {
  let writes = 0;
  context.mock.method(globalThis, 'fetch', async (path, options) => {
    if (path === '/api/state') return Response.json(snapshot);
    if (options.method === 'PUT') {
      writes++;
      throw new TypeError('Connection lost after write');
    }
    return Response.json({ profile: { ...profile, name: 'Saved remotely' }, token: 'session-token' });
  });
  const api = new RoomApi();

  await assert.rejects(api.updatePlayerDisplayName('Saved remotely'), /Cannot reach the server/);
  assert.equal((await api.loadPlayerProfile()).profile.name, 'Saved remotely');
  assert.equal(writes, 1);
});

test('rejects invalid profile responses and reports rejected names', async (context) => {
  let body = { profile: { id: 'white' } };
  let status = 200;
  context.mock.method(globalThis, 'fetch', async (path) =>
    Response.json(path === '/api/state' ? snapshot : body, { status }));
  const api = new RoomApi();
  for (const invalid of [null, [], {}, { profile: { id: 'white' } }, { profile: { id: '', name: 'Alice' } }]) {
    body = invalid;
    await assert.rejects(api.loadPlayerProfile(), /invalid (player profile|JSON response)/);
  }
  await api.state();
  body = { message: 'Enter your name.', token: 'session-token' };
  status = 400;
  await assert.rejects(api.updatePlayerDisplayName(' '), /Enter your name/);
});

test('a stale background snapshot cannot overwrite a completed profile update', async () => {
  let resolveSnapshot;
  const actions = new RoomActions({
    state: () => new Promise(resolve => { resolveSnapshot = resolve; }),
    updatePlayerDisplayName: async name => ({ profile: { ...profile, name } })
  });
  const current = { ...snapshot, pieces: new Map(Object.entries(snapshot.pieces)) };
  const synchronization = actions.synchronize(current);

  const response = await actions.updatePlayerDisplayName('Renamed');
  resolveSnapshot({ ...current, viewer: { ...profile, name: 'Old name' } });

  assert.equal(response.profile.name, 'Renamed');
  assert.equal(await synchronization, undefined);
});

test('profile failures release the action lock and game polling does not query the player service', async () => {
  let loads = 0;
  const current = { ...snapshot, pieces: new Map(Object.entries(snapshot.pieces)) };
  const actions = new RoomActions({
    state: async () => current,
    updatePlayerDisplayName: async () => { throw new Error('Unavailable'); },
    loadPlayerProfile: async () => { loads++; return { profile }; }
  });

  await assert.rejects(actions.updatePlayerDisplayName('Renamed'), /Unavailable/);
  assert.deepEqual((await actions.loadPlayerProfile()).profile, profile);
  assert.equal(await actions.synchronize(current), undefined);
  assert.equal(loads, 1);
});
