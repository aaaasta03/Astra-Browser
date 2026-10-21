(() => {
  let speed = 1;
  const directMediaExtensions = new Set([
    "mp4", "m4v", "webm", "mkv", "mov", "3gp",
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
    "m3u8", "mpd"
  ]);
  const handoffPending = new WeakSet();

  const isDirectMediaUrl = (raw) => {
    try {
      const url = new URL(raw);
      if (url.protocol !== "https:" && url.protocol !== "http:") return false;
      const extension = url.pathname.split(".").pop().toLowerCase();
      return directMediaExtensions.has(extension);
    } catch (_) {
      return false;
    }
  };

  const apply = (video) => {
    if (!(video instanceof HTMLVideoElement)) return;
    try { if (Math.abs(video.playbackRate - speed) > 0.001) video.playbackRate = speed; } catch (_) {}
  };
  const scan = (root) => {
    if (root instanceof HTMLVideoElement) apply(root);
    if (root && root.querySelectorAll) root.querySelectorAll("video").forEach(apply);
  };
  const requestSettings = () => browser.runtime.sendMessage({ type: "get-video-settings" })
    .then(settings => {
      const next = Number(settings && settings.playbackSpeed);
      if (Number.isFinite(next) && next >= 0.25 && next <= 4) speed = next;
      scan(document);
    }).catch(() => {});

  const requestNativeFullscreenVideo = () => {
    const fullscreenElement = document.fullscreenElement || document.webkitFullscreenElement;
    if (!fullscreenElement) return;
    const video = fullscreenElement instanceof HTMLVideoElement
      ? fullscreenElement
      : (typeof fullscreenElement.querySelector === "function"
          ? fullscreenElement.querySelector("video")
          : null);
    if (!(video instanceof HTMLVideoElement) || handoffPending.has(video)) return;

    const src = video.currentSrc || video.src || "";
    if (!isDirectMediaUrl(src)) return;

    handoffPending.add(video);
    browser.runtime.sendMessage({
      type: "request-native-video",
      src,
      title: document.title || "",
      positionMs: Math.max(0, Math.floor((video.currentTime || 0) * 1000))
    }).then(response => {
      if (response && response.accepted === true) {
        video.pause();
      } else {
        handoffPending.delete(video);
      }
    }).catch(() => handoffPending.delete(video));
  };

  document.addEventListener("play", event => { requestSettings(); apply(event.target); }, true);
  document.addEventListener("loadedmetadata", event => { requestSettings(); apply(event.target); }, true);
  document.addEventListener("fullscreenchange", requestNativeFullscreenVideo, true);
  document.addEventListener("webkitfullscreenchange", requestNativeFullscreenVideo, true);
  new MutationObserver(records => records.forEach(record =>
    record.addedNodes.forEach(scan)
  )).observe(document, { childList: true, subtree: true });
  requestSettings();
})();
