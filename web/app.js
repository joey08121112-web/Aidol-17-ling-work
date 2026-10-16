import {AudioSession} from './audio/session.js';
import {createMemoryWorkspace} from './memory.js';

const $ = id => document.getElementById(id);
const names = {work:'专注工作', commute:'安心通勤', life:'自在生活'};
const kinds = {task:'待办任务', fact:'有效信息', preference:'个人偏好', trip:'行程计划'};
const labels = {overview:'实时空间', memory:'全局记忆', settings:'偏好与设备', architecture:'系统架构'};
const state = {data:null, token:sessionStorage.getItem('aidol-token') || '', tab:'overview', settingsDirty:false,
  recording:false, locationWatch:null, stream:null, refreshTimer:null, assistant:'', demo:false, lastReply:'', refreshVersion:0};
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const time = value => value ? new Date(value).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}) : '';
const date = value => new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});

function toast(message) {
  $('toast').textContent = message; $('toast').classList.add('show');
  clearTimeout(state.toastTimer); state.toastTimer = setTimeout(() => $('toast').classList.remove('show'), 5500);
}
async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {...options, headers:{'Content-Type':'application/json',
    ...(state.token ? {Authorization:`Bearer ${state.token}`} : {}), ...options.headers}});
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (response.status === 401) { showTab('settings'); throw new Error('请在服务设置中填写访问口令'); }
    const detail = typeof body.detail === 'string' ? body.detail : JSON.stringify(body.detail || response.statusText);
    throw new Error(detail);
  }
  return response.json();
}
function showTab(tab) {
  state.tab = tab;
  document.querySelectorAll('.page').forEach(page => page.classList.toggle('active', page.id === `page-${tab}`));
  document.querySelectorAll('.nav').forEach(button => button.classList.toggle('active',button.dataset.tab === tab));
  $('page-label').textContent = labels[tab];
}
document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => showTab(button.dataset.tab));
document.querySelectorAll('[data-goto]').forEach(button => button.onclick = () => showTab(button.dataset.goto));

async function refresh() {
  const version = ++state.refreshVersion;
  const data = await api('/state');
  if (version !== state.refreshVersion) return;
  state.data = data;
  render(data);
  if (!state.settingsDirty) fillSettings(data.settings);
}
function scheduleRefresh() {
  clearTimeout(state.refreshTimer);
  state.refreshTimer = setTimeout(() => refresh().catch(error => toast(error.message)), 120);
}
function render(data) {
  const {agent,settings,capabilities,memories,transcripts,drafts,events} = data;
  $('auto-mode').checked = settings.auto_mode;
  document.querySelectorAll('[data-mode]').forEach(button => button.classList.toggle('selected',button.dataset.mode === agent.mode));
  $('mode-reason').textContent = agent.reason;
  audio.muted = agent.mode === 'work';
  if (audio.muted) audio.interrupt();
  $('nav-memory-count').textContent = memories.length;
  $('memory-total').textContent = `${memories.length} 条记忆`;
  $('transcript-count').textContent = transcripts.length;
  $('transcripts').innerHTML = transcripts.length ? [...transcripts].reverse().map(t => `
    <article class="transcript"><span class="speaker-icon">${escape(t.speaker.slice(0,1))}</span><div><div class="meta"><span>${escape(t.speaker)}</span><time>${time(t.created_at)}</time><em>${t.source === 'demo' ? '模拟场景' : names[t.mode] || ''}</em></div><p>${escape(t.text)}</p><button class="text-button transcript-save" data-remember="${t.id}">＋ 记住这句</button></div></article>`).join('') : '<div class="empty-state">每一句有价值的话，都值得被记住。<small>开始聆听，或在下方输入文字体验。</small></div>';
  $('transcripts').scrollTop = $('transcripts').scrollHeight;
  $('memory-preview').innerHTML = memories.length ? memories.slice(0,3).map(m => `<article class="memory-item"><span class="memory-tag">${kinds[m.kind]} / ${date(m.created_at)}</span><p>${escape(m.content)}</p></article>`).join('') : '<div class="empty-compact">这里是你的记忆口袋，<br>等待第一件值得记住的小事。</div>';
  memoryWorkspace.render(data);
  $('service-status').innerHTML = [['doubao','豆包原生实时'],['qwen','Qwen-Omni 实时'],['text_llm','豆包文本交互与清洗'],['asr','豆包独立 ASR'],['tts','豆包独立 TTS'],['amap','高德 Web 服务']].map(([key,label]) => `<div class="service-row"><span>${label}</span><span class="${capabilities[key]?'ok':'missing'}">${capabilities[key]?'已配置':'未配置'}</span></div>`).join('');
  $('text-model-info').textContent = `文本模型：${capabilities.text_model || '未配置'} · 思考关闭 · ASR：${capabilities.asr_mode || '未配置'}`;
  if (!state.recording) $('provider-badge').textContent = settings.provider === 'cascade' ? '豆包 ASR → Turbo → TTS' : capabilities.doubao ? '豆包实时语音 · 已配置' : capabilities.qwen ? 'Qwen-Omni · 已配置' : '文字演示可用';
  const route = agent.route;
  $('trip-content').innerHTML = route?.available ? `<div class="route-info">前往 ${escape(route.destination)}<br><strong>${Math.max(1,Math.round(route.eta_seconds/60))}</strong> 分钟 · ${(route.distance_meters/1000).toFixed(1)} km<small>${escape(route.note)}</small></div>` : `<div class="empty-compact">${escape(route?.reason || (agent.location ? '位置已更新，等待通勤模式与目的地。' : '开启位置感知后，让下一段旅程更从容。'))}</div>`;
  $('drafts').innerHTML = drafts.filter(d => d.status === 'pending').map(d => `<article class="draft"><strong>需要通知 ${escape(d.contact)} 吗？</strong><p>${escape(d.text)}</p><button data-copy="${d.id}">确认并复制</button><button data-dismiss="${d.id}">暂不需要</button><small>仅复制草稿，不自动发送</small></article>`).join('');
  $('event-list').innerHTML = events.length ? events.map(event => `<div class="event-row"><time>${time(event.created_at)}</time><code>${escape(event.type)}</code><p>${escape(JSON.stringify(event.payload))}</p></div>`).join('') : '<div class="empty-compact">系统事件会在这里实时出现</div>';
}
$('transcripts').onclick=async event=>{
  const button=event.target.closest('[data-remember]');if(!button)return;button.disabled=true;
  try{await api(`/memories/from-transcript/${button.dataset.remember}`,{method:'POST'});await refresh();toast('已保存原话，可在记忆时间线中收藏、分类或更正');}
  catch(error){toast(error.message);}finally{button.disabled=false;}
};
const memoryWorkspace = createMemoryWorkspace({api, toast, refresh, getData:()=>state.data, speak:speakResponse, escape, date});
$('drafts').onclick = async event => {
  const id = event.target.dataset.copy || event.target.dataset.dismiss;
  if (!id) return;
  try {
    if (event.target.dataset.copy) {
      const draft = state.data.drafts.find(d => d.id === id);
      await navigator.clipboard.writeText(draft.text);
      await api(`/drafts/${id}/copied`,{method:'POST'}); toast('草稿已复制，请在聊天软件中粘贴发送');
    } else await api(`/drafts/${id}/dismissed`,{method:'POST'});
    await refresh();
  } catch(error) {toast(`处理失败：${error.message}`);}
};

const settingsFields = {name:'name',preferences:'preferences',provider:'provider',work_start:'work-start',work_end:'work-end',timezone:'timezone',
  life_poll_seconds:'interval',listen_seconds:'duration',vad_threshold:'threshold',destination_name:'destination',destination_lon:'lon',destination_lat:'lat',contact:'contact',arrival_notice_minutes:'notice',transport:'transport',transit_city:'transit-city'};
const checkFields = {life_poll_enabled:'poll',vad_enabled:'vad',retain_transcripts:'retain'};
function fillSettings(settings) {
  for (const [key,id] of Object.entries(settingsFields)) $(`setting-${id}`).value = settings[key] ?? '';
  for (const [key,id] of Object.entries(checkFields)) $(`setting-${id}`).checked = settings[key];
}
document.querySelectorAll('[id^="setting-"]').forEach(input => input.oninput = () => {state.settingsDirty=true;});
$('save-settings').onclick = async () => {
  if (!state.data) return toast('请先连接后端');
  try {
    const settings = {...state.data.settings};
    for (const [key,id] of Object.entries(settingsFields)) {
      const element = $(`setting-${id}`);
      if (!element.reportValidity()) return;
      settings[key] = element.type === 'number' ? (element.value === '' ? null : Number(element.value)) : element.value;
    }
    for (const [key,id] of Object.entries(checkFields)) settings[key] = $(`setting-${id}`).checked;
    await api('/settings',{method:'PUT',body:JSON.stringify(settings)}); state.settingsDirty=false; await refresh();
    toast(state.recording ? '设置已保存，模型与语音会话参数在下次连接生效' : '设置已保存');
  } catch(error) {toast(error.message);}
};
async function patchSettings(patch) {
  if (!state.data) throw new Error('后端尚未连接');
  await api('/settings',{method:'PUT',body:JSON.stringify({...state.data.settings,...patch})}); await refresh();
}
document.querySelectorAll('[data-mode]').forEach(button => button.onclick = () => patchSettings({mode:button.dataset.mode,auto_mode:false}).catch(error => toast(error.message)));
$('auto-mode').onchange = event => patchSettings({auto_mode:event.target.checked}).catch(error => toast(error.message));

const audio = new AudioSession(event => {
  if (event.type === 'ready') {
    $('audio-state').textContent = '正在聆听 · 麦克风已开启';
    $('audio-hint').textContent = '音频流传输中 · 原始音频不落盘';
    $('provider-badge').textContent = `${{doubao:'豆包实时',qwen:'Qwen-Omni',cascade:'ASR → Turbo → TTS'}[event.provider] || event.provider} · 已连接`;
    $('hero-title').textContent = '嗯，我在听。';
  } else if (event.type === 'transcript.delta') $('partial').textContent = `正在识别：${event.text}`;
  else if (event.type === 'transcript') { $('partial').textContent = ''; state.assistant=''; }
  else if (event.type === 'assistant.delta') { state.assistant += event.text; state.lastReply=state.assistant; $('partial').textContent = `聆：${state.assistant.slice(-300)}`; }
  else if (event.type === 'error' || event.type === 'provider.failed') toast(event.message);
  else if (event.type === 'closed') setRecording(false);
  else if (event.type === 'metric') $('audio-hint').textContent = `连接至首次音频 ${event.value} ms（含等待发言）`;
}, level => {
  const intensity = Math.min(1,level*8);
  document.querySelectorAll('#voice-bars i').forEach((bar,index) => {
    bar.style.height = `${5 + intensity * (10 + 20*Math.abs(Math.sin(index*.8 + performance.now()/180))) }px`;
  });
});
function setRecording(value) {
  state.recording = value; document.body.classList.toggle('recording',value);
  $('record-button').textContent = value ? '■ 结束聆听' : '◉ 开始聆听';
  if (!value) {
    $('audio-state').textContent = '麦克风未开启'; $('audio-hint').textContent = '由你开始，随时暂停';
    $('hero-title').textContent = '我在这里，听你说。'; $('partial').textContent = '';
    scheduleRefresh();
  }
}
async function startAudio(duration) {
  if (state.recording) return;
  if (!state.data) return toast('请先连接后端');
  const caps=state.data.capabilities;
  if (state.data.settings.provider === 'cascade') {
    if (!caps.asr || !caps.text_llm || !caps.tts) return toast('豆包链路需要同时配置独立 ASR、文本模型与 TTS');
  } else if (!caps.doubao && !caps.qwen) return toast('请先配置豆包或 Qwen Key；目前可以使用文字输入与一键演示');
  setRecording(true); $('audio-state').textContent = '正在连接语音服务…';
  try {
    await audio.start({input:$('input-device').value,output:$('output-device').value,token:state.token,duration,
      threshold:state.data.settings.vad_threshold, vad:state.data.settings.vad_enabled});
  } catch(error) {setRecording(false); toast(`无法开始录音：${error.message}`);}
}
$('record-button').onclick = () => state.recording ? audio.stop() : startAudio();
$('refresh-devices').onclick = async () => {
  try {
    const devices = await audio.hardware.devices(true);
    for (const [kind,id] of [['audioinput','input-device'],['audiooutput','output-device']]) {
      const selected = $(id).value;
      $(id).innerHTML = '<option value="">系统默认设备</option>' + devices.filter(device => device.kind === kind).map((device,index) => `<option value="${escape(device.deviceId)}">${escape(device.label || `设备 ${index+1}`)}</option>`).join('');
      if ([...$(id).options].some(option => option.value === selected)) $(id).value = selected;
    }
    toast('设备列表已更新。请明确选择 Viaim 麦克风与输出');
  } catch(error) {toast(`设备授权失败：${error.message}`);}
};
$('input-device').onchange = () => {
  const label = $('input-device').selectedOptions[0].textContent;
  $('device-name').textContent = label; $('device-status').textContent = '输入设备已选择 · 下次录音生效';
};
$('test-output').onclick = async () => {
  const context = new AudioContext();
  try {
    await context.resume(); await audio.hardware.setOutput(context,$('output-device').value);
    const oscillator = context.createOscillator(), gain = context.createGain();
    oscillator.frequency.value=523.25; gain.gain.value=.08; oscillator.connect(gain); gain.connect(context.destination);
    oscillator.start(); gain.gain.exponentialRampToValueAtTime(.001,context.currentTime+.7); oscillator.stop(context.currentTime+.8);
    oscillator.onended = () => context.close(); toast('测试音已播放，请确认声音来自耳机');
  } catch(error) {await context.close(); toast(error.message);}
};
$('text-form').onsubmit = async event => {
  event.preventDefault(); const text = $('text-input').value.trim(); if (!text) return;
  const button = event.target.querySelector('button'); button.disabled=true;
  try {
    const response = await api('/text',{method:'POST',body:JSON.stringify({text,speaker:$('speaker').value || '我',event_id:crypto.randomUUID()})});
    $('text-input').value=''; await refresh();
    if (response.response) {state.lastReply=response.response; $('partial').textContent=`聆：${response.response}`;}
  } catch(error) {toast(error.message);} finally {button.disabled=false;}
};
$('location-button').onclick = () => {
  if (state.locationWatch !== null) {
    navigator.geolocation.clearWatch(state.locationWatch); state.locationWatch=null;
    $('location-button').textContent='⌖ 开启位置感知'; toast('位置感知已关闭'); return;
  }
  if (!navigator.geolocation) return toast('浏览器不支持定位');
  let lastUpdate=0;
  state.locationWatch=navigator.geolocation.watchPosition(async position => {
    if (Date.now()-lastUpdate < 10000) return; lastUpdate=Date.now();
    try {
      await api('/location',{method:'POST',body:JSON.stringify({longitude:position.coords.longitude,latitude:position.coords.latitude,
        accuracy:position.coords.accuracy,speed:position.coords.speed === null ? null : Math.max(0,position.coords.speed)})}); await refresh();
    } catch(error) {toast(error.message);}
  }, error => {
    toast(`定位失败：${error.message}`);
    if (error.code === 1) { navigator.geolocation.clearWatch(state.locationWatch);state.locationWatch=null;$('location-button').textContent='⌖ 开启位置感知'; }
  },{enableHighAccuracy:true,maximumAge:15000,timeout:15000});
  $('location-button').textContent='⌖ 停止位置感知';
};
$('export-button').onclick=async()=>{
  try {const data=await api('/export');const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));
    const a=document.createElement('a');a.href=url;a.download=`aidol-memory-${new Date().toISOString().slice(0,10)}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  } catch(error) {toast(error.message);}
};
async function speakResponse(text) {
  const result=await fetch('/api/speech/synthesize',{method:'POST',headers:{'Content-Type':'application/json',
    ...(state.token?{Authorization:`Bearer ${state.token}`}:{})},body:JSON.stringify({text:text.slice(0,1500)})});
  if(!result.ok)throw new Error((await result.json()).detail || '合成失败');
  if(state.data?.agent.mode !== 'work') await audio.speakWav(await result.arrayBuffer(),$('output-device').value);
}
$('speak-reply').onclick=async()=>{
  if (!state.lastReply) return toast('先输入一句话，收到回复后可以朗读');
  if (state.data?.agent.mode === 'work') return toast('工作模式保持安静，请切换生活模式后播放');
  const button=$('speak-reply');button.disabled=true;
  try {
    await speakResponse(state.lastReply);
  }catch(error){toast(error.message);}finally{button.disabled=false;}
};
$('transcribe-file').onclick=async()=>{
  const file=$('asr-file').files[0];
  if(!file)return toast('请选择 WAV 或 PCM 文件');
  if(file.size>2*1024*1024)return toast('文件不能超过 2 MB');
  const button=$('transcribe-file');button.disabled=true;
  try{
    const response=await fetch('/api/speech/transcribe',{method:'POST',headers:{'Content-Type':file.name.toLowerCase().endsWith('.pcm')?'audio/pcm':'audio/wav',
      ...(state.token?{Authorization:`Bearer ${state.token}`}:{})},body:file});
    const result=await response.json();if(!response.ok)throw new Error(result.detail || '识别失败');
    await refresh();toast(result.text?`识别成功：${result.text}`:'未识别到有效语音');
  }catch(error){toast(error.message);}finally{button.disabled=false;}
};
$('search-place').onclick=async()=>{
  const query=$('place-query').value.trim();if(!query)return toast('请输入地点名称');
  const button=$('search-place');button.disabled=true;
  try{
    const result=await api(`/maps/search?q=${encodeURIComponent(query)}&city=${encodeURIComponent($('setting-transit-city').value)}`);
    state.places=result.pois || [];
    $('place-results').innerHTML=state.places.length?state.places.map((p,index)=>`<button class="place-item" data-place="${index}"><strong>${escape(p.name)}</strong><small>${escape(p.city)} · ${escape(p.address)}</small><span>设为目的地 ↗</span></button>`).join(''):`<p class="notice">${escape(result.reason||'未找到地点')}</p>`;
  }catch(error){toast(error.message);}finally{button.disabled=false;}
};
$('place-results').onclick=event=>{
  const target=event.target.closest('[data-place]');if(!target)return;
  const place=state.places[Number(target.dataset.place)];
  $('setting-destination').value=place.name;$('setting-lon').value=place.longitude;$('setting-lat').value=place.latitude;
  state.settingsDirty=true;toast('目的地已填入，请点击保存设置');
};
$('nearby-button').onclick=async()=>{
  const location=state.data?.agent.location;
  if(!location)return toast('请先开启位置感知，再查询附近地点');
  const button=$('nearby-button');button.disabled=true;
  try{
    const result=await api('/maps/nearby',{method:'POST',body:JSON.stringify({...location,keyword:$('nearby-kind').value})});
    $('nearby-results').innerHTML=result.pois?.length?result.pois.slice(0,4).map(p=>`<div class="nearby-item"><strong>${escape(p.name)}</strong><small>${p.distance_meters!=null?`${p.distance_meters}m · `:''}${escape(p.address)}</small></div>`).join(''):`<p class="notice">${escape(result.reason||'暂无附近地点')}</p>`;
  }catch(error){toast(error.message);}finally{button.disabled=false;}
};
$('clear-data').onclick=async()=>{
  if (!confirm('确认清空全部转写、记忆与通知？此操作不可撤销，建议先导出。')) return;
  try {
    await audio.stop();
    // The server may still be draining final ASR text after the local device closes.
    for(let attempt=0;attempt<30;attempt++) {
      const snapshot=await api('/state');
      if(!snapshot.agent.audio_clients)break;
      await new Promise(resolve=>setTimeout(resolve,200));
    }
    await api('/data',{method:'DELETE'});await refresh();toast('本地数据已清空');
  } catch(error){toast(error.message);}
};

async function connectEvents() {
  clearTimeout(state.eventRetryTimer);
  state.stream?.abort(); const controller=new AbortController();state.stream=controller;
  try {
    const response=await fetch('/api/events',{signal:controller.signal,headers:state.token?{Authorization:`Bearer ${state.token}`}:{}});
    if (!response.ok) throw new Error('事件连接失败');
    $('connection').textContent='后端已连接';$('connection').classList.add('connected');
    // SSE has no replay: restore changes missed while the connection was down.
    await refresh();
    const reader=response.body.getReader(), decoder=new TextDecoder();let buffer='';
    while (true) {
      const {value,done}=await reader.read();if(done)break;
      buffer+=decoder.decode(value,{stream:true});
      let end;
      while((end=buffer.indexOf('\n\n'))>=0){
        const frame=buffer.slice(0,end);buffer=buffer.slice(end+2);
        if(!frame.startsWith('data: '))continue;
        const event=JSON.parse(frame.slice(6));
        if(event.type==='life.listen_requested' && $('poll-consent').checked && !document.hidden && !state.recording){
          toast(`定时倾听：接下来监听 ${event.payload.seconds} 秒`); await startAudio(event.payload.seconds);
        } else if(event.type==='notification.proposed') {
          toast(event.payload.message);state.lastReply=event.payload.message;
          if(event.payload.origin==='location' && !state.demo && state.locationWatch!==null && state.data?.agent.mode!=='work' && state.data?.capabilities.tts)
            speakResponse(event.payload.message).catch(error=>toast(`通知已生成，语音播放失败：${error.message}`));
        }
        if(event.type!=='life.listen_requested') scheduleRefresh();
      }
    }
  } catch(error) {if(error.name==='AbortError')return;}
  if(!controller.signal.aborted){$('connection').textContent='连接中断 · 重试中';$('connection').classList.remove('connected');state.eventRetryTimer=setTimeout(connectEvents,3000);}
}
$('access-token').value=state.token;
$('apply-token').onclick=async()=>{
  state.token=$('access-token').value;sessionStorage.setItem('aidol-token',state.token);
  try {await refresh();connectEvents();toast('已重新连接');} catch(error){toast(error.message);}
};
$('demo-button').onclick=async()=>{
  if(state.demo)return;state.demo=true;$('demo-button').disabled=true;
  const original=state.data ? {...state.data.settings} : null;
  try {
    await audio.stop(); await patchSettings({auto_mode:true});
    const scenes=[['林同学','开始会议，讨论需求。我负责前端，明天下午三点前完成耳机控制台。'],
      ['陈同学','我负责后端，周五前完成语音接口和记忆持久化。'],['我','嗯嗯，呃，好。'],
      ['我','开始通勤，我正在坐地铁去公司。'],['到站播报','下一站即将到站，请提前做好下车准备。'],
      ['我','结束会议，休息一下。我喜欢清淡的食物，不吃香菜。'],['我','给我讲个笑话。']];
    for(const [speaker,text] of scenes){
      const result=await api('/text',{method:'POST',body:JSON.stringify({text,speaker,source:'demo',event_id:crypto.randomUUID()})});
      await refresh();if(result.response){state.lastReply=result.response;$('partial').textContent=`聆：${result.response}`;}
      await new Promise(resolve=>setTimeout(resolve,650));
    }
    toast('演示完成：工作记录 → 通勤提醒 → 生活偏好。所有模拟发言已标记来源。');
  } catch(error){toast(error.message);}finally{
    if(original)try{await api('/settings',{method:'PUT',body:JSON.stringify(original)});await refresh();}catch{}
    state.demo=false;$('demo-button').disabled=false;
  }
};
window.addEventListener('beforeunload',()=>{state.stream?.abort();audio.stop();if(state.locationWatch!==null)navigator.geolocation.clearWatch(state.locationWatch);});
setInterval(()=>$('clock').textContent=new Date().toLocaleDateString('zh-CN',{month:'long',day:'numeric',weekday:'short'}),1000);
refresh().then(connectEvents).catch(error=>{toast(error.message);$('connection').textContent='等待连接';});
