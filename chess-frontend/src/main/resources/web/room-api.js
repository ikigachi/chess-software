export class RoomApi {
  #token;

  async state() {
    return this.#requestRoomSnapshot('/api/state');
  }

  async loadPlayerProfile() {
    return this.#requestPlayerProfile();
  }

  async updatePlayerDisplayName(name) {
    if (!this.#token) await this.state();
    return this.#requestPlayerProfile({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, token: this.#token })
    });
  }

  async #requestPlayerProfile(options) {
    const response = await this.#request('/api/profile', options);
    if (typeof response.profile?.id !== 'string' || !response.profile.id ||
        typeof response.profile?.name !== 'string' || !response.profile.name.trim()) {
      throw new Error('The server returned an invalid player profile.');
    }
    return response;
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
    return this.#requestRoomSnapshot('/api/action', {
      method: 'POST',
      body: new URLSearchParams({ ...fields, action, token: this.#token })
    });
  }

  async #requestRoomSnapshot(path, options) {
    const snapshot = await this.#request(path, options);
    return { ...snapshot, pieces: new Map(Object.entries(snapshot.pieces)) };
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
      if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) throw new Error();
    } catch {
      throw new Error('The server returned an invalid JSON response.');
    }
    if (snapshot.token) this.#token = snapshot.token;
    if (!response.ok) {
      if (response.status === 403 && !snapshot.token) this.#token = undefined;
      throw new Error(snapshot.message || 'The request could not be completed.');
    }
    return snapshot;
  }
}
