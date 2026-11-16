import { startApp } from './app.js';
import { RoomActions } from './room-actions.js';
import { RoomApi } from './room-api.js';

startApp(new RoomActions(new RoomApi()));
