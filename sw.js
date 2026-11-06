self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SHOW_IOS_NOTIFICATION') {
    self.registration.showNotification(event.data.title || 'HydroPulse Reminder', {
      body: event.data.body || 'Time to drink water!',
      icon: './apple-touch-icon.png',
      badge: './apple-touch-icon.png',
      vibrate: [250, 120, 250]
    });
  }
});
