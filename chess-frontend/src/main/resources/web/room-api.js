export class RoomApi {
  #token;

  async state() {
    return this.#request('/api/state');
  }

  async action(action, fields = {}) {
    if (!this.#token) await this.state();
    if (action === 'move' && !fields.code) throw new Error('Enter the room code.');
    if (action === 'create' || action === 'move') {
      const path = action === 'create' ? '/games' : `/games/${encodeURIComponent(fields.code)}`;
      const body = action === 'create' ? { name: fields.name }
        : { from: fields.from, to: fields.to, promotion: fields.promotion };
      await this.#request(path, {
        method: action === 'create' ? 'POST' : 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...body, token: this.#token })
      });
      return this.state();
    }
    return this.#request('/api/action', {
      method: 'POST',
      body: new URLSearchParams({ ...fields, action, token: this.#token })
    });
  }

  async #request(path, options = {}) {
    let response;
    try {
      response = await fetch(path, { ...options, credentials: 'same-origin', signal: AbortSignal.timeout(8000) });
    } catch {
      throw new Error('Cannot reach the server. Check that it is running and try again.');
    }
    let snapshot;
    try {
      snapshot = await response.json();
    } catch {
      throw new Error('The server returned an invalid JSON response.');
    }
    if (snapshot.token) this.#token = snapshot.token;
    if (!response.ok) {
      if (response.status === 403 && !snapshot.token) this.#token = undefined;
      throw new Error(snapshot.message || 'The request could not be completed.');
    }
    return { ...snapshot, pieces: new Map(Object.entries(snapshot.pieces)) };
  }
}
