const NATIVE_APP = "org.aaaasta03.astrabrowser.video";
browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || message.type !== "get-video-settings") return undefined;
  return browser.runtime.sendNativeMessage(NATIVE_APP, { type: "get-video-settings" })
    .catch(() => ({ playbackSpeed: 1, autoLandscape: true, immersiveFullscreen: true }));
});
