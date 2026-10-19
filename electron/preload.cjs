const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('smartFarmDesktop', Object.freeze({ isDesktop: true, appName: 'SmartFarm360', version: process.versions.electron }));
