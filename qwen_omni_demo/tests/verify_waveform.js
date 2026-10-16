(function () {
  const out = {};
  const bars = document.querySelectorAll("#waveform i");
  out.barCount = bars.length;
  out.initTransforms = [...bars].slice(0, 4).map(b => b.style.transform);
  out.idleClassAtInit = document.querySelector("#waveform").classList.contains("is-idle");

  // 模拟一段语音：喂正弦包络
  resetWaveform({ idle: false });
  for (let i = 0; i < 70; i += 1) {
    const env = Math.sin((i / 70) * Math.PI);
    pushWaveformLevel(env * 0.75);
  }
  return new Promise(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const now = [...document.querySelectorAll("#waveform i")].map(b => b.style.transform);
      out.afterTransforms = now.slice(0, 6);
      out.distinctValues = new Set(now).size;
      out.maxScale = Math.max(...now.map(t => Number(/([\d.]+)\)/.exec(t)[1])));
      // 静音后应回落
      resetWaveform();
      out.idleAfterReset = document.querySelector("#waveform").classList.contains("is-idle");
      resolve(JSON.stringify(out, null, 2));
    }));
  });
})()
