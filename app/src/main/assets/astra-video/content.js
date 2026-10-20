(() => {
  let speed = 1;
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
  document.addEventListener("play", event => apply(event.target), true);
  document.addEventListener("loadedmetadata", event => apply(event.target), true);
  new MutationObserver(records => records.forEach(record =>
    record.addedNodes.forEach(scan)
  )).observe(document, { childList: true, subtree: true });
  requestSettings();
})();
