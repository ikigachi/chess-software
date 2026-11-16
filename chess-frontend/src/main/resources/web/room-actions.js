export class RoomActions {
  #service;
  #performing = false;
  #synchronizing = false;
  #actionVersion = 0;
  #syncFailed = false;

  constructor(service) {
    this.#service = service;
  }

  async load() {
    return this.#service.state();
  }

  async perform(action, fields = {}) {
    if (this.#performing) return;
    this.#performing = true;
    this.#actionVersion++;
    this.#syncFailed = false;
    try {
      return await this.#service.action(action, fields);
    } finally {
      this.#performing = false;
    }
  }

  async recoverAfter(action) {
    if (action !== 'move') return;
    try {
      return await this.load();
    } catch {}
  }

  async synchronize(current) {
    if (this.#performing || this.#synchronizing || !current?.room) return;
    const version = this.#actionVersion;
    this.#synchronizing = true;
    try {
      const next = await this.load();
      if (this.#performing || version !== this.#actionVersion) return;
      const recovered = this.#syncFailed;
      this.#syncFailed = false;
      if (stateKey(next) !== stateKey(current)) return { snapshot: next };
      if (presenceChanged(current.presence, next.presence)) return { snapshot: next, presenceOnly: true };
      if (recovered) return { recovered: true };
    } catch (error) {
      if (this.#performing || version !== this.#actionVersion) return;
      this.#syncFailed = true;
      throw error;
    } finally {
      this.#synchronizing = false;
    }
  }
}

function stateKey(state) {
  return JSON.stringify([state.viewer, state.room, [...state.pieces].sort()]);
}

function presenceChanged(previous, current) {
  return previous?.whiteConnected !== current?.whiteConnected || previous?.blackConnected !== current?.blackConnected;
}
