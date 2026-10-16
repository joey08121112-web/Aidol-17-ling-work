/** Hardware boundary. Replace only this adapter when Viaim provides its SDK. */
export class BrowserHeadset {
  capabilities() {
    return {input: !!navigator.mediaDevices?.getUserMedia, outputSelection: 'setSinkId' in AudioContext.prototype,
      anc: false, buttons: false, battery: false, transport: 'browser-bluetooth'};
  }
  async devices(requestPermission = false) {
    if (!navigator.mediaDevices) throw new Error('麦克风需要 HTTPS 或 localhost');
    if (requestPermission) {
      const stream = await navigator.mediaDevices.getUserMedia({audio: true});
      stream.getTracks().forEach(track => track.stop());
    }
    return navigator.mediaDevices.enumerateDevices();
  }
  async capture(deviceId) {
    return navigator.mediaDevices.getUserMedia({audio: {
      deviceId: deviceId ? {exact: deviceId} : undefined,
      channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true
    }});
  }
  async setOutput(context, deviceId) {
    if (typeof context.setSinkId === 'function') await context.setSinkId(deviceId || '');
    else if (deviceId) throw new Error('浏览器不支持选择输出，请在系统声音设置中选择 Viaim');
  }
  async setNoiseCancellation() {
    throw new Error('Viaim ANC 控制等待厂商 SDK，请使用耳机按键或官方 App');
  }
}

export class ViaimSDKAdapter extends BrowserHeadset {
  // Integrate vendor SDK here; advertise a capability only after real handshake.
  constructor(sdk) { super(); this.sdk = sdk; }
  capabilities() { return {...super.capabilities(), transport: 'viaim-sdk-pending'}; }
}
