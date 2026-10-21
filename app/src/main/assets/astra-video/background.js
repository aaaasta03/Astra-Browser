const NATIVE_APP = "org.aaaasta03.astrabrowser.video";

browser.runtime.onMessage.addListener((message, sender) => {
  if (!message) return undefined;

  if (message.type === "request-native-video") {
    return browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: "request-native-video",
      src: message.src,
      title: message.title,
      positionMs: message.positionMs
    }).catch(() => ({ accepted: false }));
  }

  if (message.type !== "get-video-settings") return undefined;
  return browser.runtime.sendNativeMessage(NATIVE_APP, { type: "get-video-settings" })
    .catch(() => ({ playbackSpeed: 1, autoLandscape: true, immersiveFullscreen: true }));
});
