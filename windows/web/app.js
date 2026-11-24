const $=id=>document.getElementById(id);let state=null,lastFocus=0,modeSignature="",awbSignature="";
const hints={'Validation / Daylight':'Camera quality baseline with automatic exposure and focus',Capture:'Live telescope feed and camera control',Planetary:'Lucky-imaging workflow for Jupiter, Saturn and Mars','Lunar / Solar':'Surface-detail capture with clipping and focus aids','Deep Sky':'RAW still sequences, calibration frames and long-exposure sessions','Live Stack':'Register and stack light frames',Focus:'Large, readable focus metrics at the telescope',Sessions:'Target, files, telemetry and processing history'};
async function api(path, body) {
  const options = {signal: AbortSignal.timeout(12000), cache: 'no-store'};
  if (body !== undefined) {
    options.method = 'POST';
    options.headers = {'Content-Type':'application/json'};
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch (error) {
    throw new Error(error.name === 'TimeoutError' ? 'The command timed out. Check the current camera state before retrying.' : 'The desktop service could not be reached.');
  }
  const data = await response.json();
  if (!response.ok || data.ok === false) throw new Error(data.error || response.statusText);
  return data;
}
let noticeTimer;
function notify(message) {
  const notice = $('dashboardNotice');
  notice.textContent = message?.message || String(message);
  notice.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.hidden = true; }, 8000);
}
function confirmAction(message) {
  const dialog = $('confirmDashboard');
  $('confirmDashboardText').textContent = message;
  dialog.returnValue = 'cancel';
  return new Promise(resolve => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'continue'), {once:true});
    dialog.showModal();
  });
}
$('exitDashboard').onclick = async () => {
  if (!await confirmAction('Exit TOD Astro Suite? Active desktop transfers and captures may be interrupted.')) return;
  try {
    await api('/api/exit', {});
    exited = true;
    notify('TOD Astro Suite has closed. You can close this tab.');
  } catch (error) { notify(error); }
};
function setConnected(device) {
  const on = device.connected;
  const label = device.transport || 'Offline';
  for (const id of ['sideDot','footerDot','statusPill']) $(id).classList.toggle('offline', !on);
  $('statusText').textContent = label;
  $('sideStatus').textContent = label;
  $('usbHint').textContent = device.usb?.detail || '';
  $('offlineState').style.display = on ? 'none' : 'block';
  renderPreviewHealth();
}

let exited=false, serverOnline=false, lastCapturePhase='';
function n(v,d=1){return Number(v||0).toFixed(d)}
function render(s){state=s;const d=s.device,m=s.metrics,seq=s.sequence,ss=s.session;setConnected(d);$('sideHost').textContent=d.usb?.detail || 'Looking for a USB phone';$('footerStatus').textContent=d.connected ? (m.live ? 'Preview live' : 'Phone connected') : d.status;$('footerDetail').textContent=d.usb?.state==='unauthorized' ? d.usb.detail : d.last_error|| (d.connected?`Camera ${d.camera||'ready'}`:'Auto-discovery runs in the background.');$('fpsBadge').textContent=n(m.fps,1)+' FPS';$('bitrateBadge').textContent=n(m.bitrate_mbps,2)+' Mb/s';$('focusMetric').textContent=n(m.focus_score,1);$('focusBig').textContent=n(m.focus_score,1);$('focusTrend').textContent=m.focus_score>lastFocus?'▲ improving':m.focus_score<lastFocus?'▼ softer':'stable';lastFocus=m.focus_score;$('meanMetric').textContent=n(m.mean,0);$('shadowMetric').textContent=n(m.shadow_clip,2)+'%';$('highlightMetric').textContent=n(m.highlight_clip,2)+'%';$('resBadge').textContent=capText('preview_width','preview_height');drawHist(currentMode()==='Live Stack' && $('showStack').checked && s.stack?.accepted ? s.stack.histogram : m.histogram||[]);renderFocus(m);$('seqStatus').textContent=seq.message||'Idle';if(seq.phase==='failed' && lastCapturePhase!=='failed')notify(d.last_error||seq.message);lastCapturePhase=seq.phase;$('integration').textContent=seq.integration_us ? `Light integration ${n(seq.integration_us / 1e6, 2)} s` : '';$('seqCount').textContent=`${seq.completed||0} / ${seq.requested||0}`;$('seqBar').style.width=(seq.requested?Math.min(100,(seq.completed/seq.requested)*100):0)+'%';$('sirilStatus').textContent=ss.siril_status || (ss.siril_path?'Detected':'Not detected');$('fileCount').textContent=(ss.captured_files||[]).length;$('pcFolder').textContent='Session: '+(ss.current_dir||'ready').split('\\').slice(-3).join(' / ');renderTelemetry(d.telemetry||{});renderCaps(d.capabilities||{});renderStack(s.stack);renderWorkflow(s);renderImaging(s);}
function capText(a,b){const c=state?.device?.capabilities||{};return c[a]&&c[b]?`${c[a]}×${c[b]}`:'—'}
function yes(x){return x===true?'YES':x===false?'NO':'—'}
let cameraOptionsKey='', cameraSelecting=false, meteringOptionsKey='';
function renderCaps(c) {
  const boxes = $('capGrid').children;
  boxes[0].querySelector('b').textContent = yes(c.raw_session_available);
  boxes[1].querySelector('b').textContent = yes(c.manual_sensor);
  boxes[2].querySelector('b').textContent = yes(c.high_speed_video);
  boxes[3].querySelector('b').textContent = yes(c.burst_capture);
  const supported = {
    manualToggle: c.manual_sensor, iso: c.manual_sensor, exposure: c.manual_sensor,
    manualFocus: c.min_focus_distance > 0, focus: c.min_focus_distance > 0, aeLock: c.ae_lock_supported, awbLock: c.awb_lock_supported,
    ois: c.ois_modes?.includes(1), eis: c.eis_modes?.includes(1), roi: c.max_zoom > 1 && !$('fpsMode').value.endsWith(' • HS')
  };
  for (const [id, available] of Object.entries(supported)) {
    $(id).disabled = !available;
    $(id).closest('label').hidden = !available;
  }
  $('focus').disabled ||= !$('manualFocus').checked;
  $('focus').title = $('focus').disabled ? 'Enable manual focus' : '0 diopters is infinity';
  $('iso').disabled ||= !$('manualToggle').checked;
  $('exposure').disabled ||= !$('manualToggle').checked;
  $('isoRange').textContent = c.iso_max ? `${c.iso_min}–${c.iso_max}` : '';
  $('iso').min = c.iso_min || '';
  $('iso').max = c.iso_max || '';
  $('exposure').min = c.exposure_us_min || '';
  $('exposure').max = c.exposure_us_max || '';
  $('focus').min = '0';
  $('focus').max = c.min_focus_distance || '';
  const format = $('format');
  for (const option of format.options) option.hidden = option.disabled = option.value !== 'jpeg' && !c.raw_session_available;
  if (format.selectedOptions[0]?.disabled) format.value = 'jpeg';
  for (const option of $('roi').options) {
    const percent = parseInt(option.value, 10) || 100;
    option.disabled = option.hidden = (c.max_zoom || 1) * percent < 100;
  }
  if ($('roi').selectedOptions[0]?.disabled) $('roi').selectedIndex = 0;
  const wbNames = ['Off', 'Auto', 'Incandescent', 'Fluorescent', 'Warm fluorescent', 'Daylight', 'Cloudy daylight', 'Twilight', 'Shade'];
  const awb = Array.isArray(c.awb_modes) ? c.awb_modes.filter(n => n > 0 && n < wbNames.length) : [];
  if (awb.join() !== awbSignature) {
    const value = $('awbMode').value;
    $('awbMode').replaceChildren(...awb.map(n => new Option(wbNames[n], n)));
    $('awbMode').value = awb.includes(+value) ? value : '1';
    awbSignature = awb.join();
  }
  $('awbMode').closest('label').hidden = awb.length < 2;
  const modes = [
    ...(Array.isArray(c.video_modes) ? c.video_modes : []),
    ...(Array.isArray(c.high_speed_modes) ? c.high_speed_modes.map(mode => mode + ' • HS') : [])
  ].filter(mode => /^\d+×\d+ @ \d+(?:-\d+)? fps(?: • HS)?$/.test(mode));
  const signature = modes.join('|') || 'unavailable';
  if (signature !== modeSignature) {
    const previous = $('fpsMode').value;
    $('fpsMode').replaceChildren(...(modes.length ? modes : ['Video unavailable']).map(mode => new Option(mode)));
    const preferred = modes.find(mode => /^1920×1080 @ 30 fps$/.test(mode))
      || modes.find(mode => /^1920×1080/.test(mode)) || modes[0];
    if (modes.includes(previous)) $('fpsMode').value = previous;
    else if (preferred) $('fpsMode').value = preferred;
    modeSignature = signature;
  }
  $('fpsMode').disabled = !modes.length;
  $('startVideoBtn').disabled = !modes.length || state.video.recording || state.video.message === 'Starting';
  $('stopVideoBtn').disabled = !state.video.recording;
  $('videoState').textContent = state.video.message;
  $('highSpeedNote').hidden = !$('fpsMode').value.endsWith(' • HS');
  const cameras = (c.camera_inventory || []).filter(camera => camera.openable && camera.jpeg_sizes?.length && camera.yuv_sizes?.length);
  const cameraKey = cameras.map(camera => camera.id).join('|');
  const selector = $('cameraSelect');
  if (cameraKey !== cameraOptionsKey) {
    selector.replaceChildren(...cameras.map(camera => new Option(
      `Camera ${camera.id} (${camera.facing===1 ? 'rear' : camera.facing===0 ? 'front' : 'external'})`, camera.id)));
    cameraOptionsKey=cameraKey;
  }
  if (!cameraSelecting && c.camera_id) selector.value=c.camera_id;
  selector.disabled = !state.device.connected || cameraSelecting || state.sequence.running || state.video.recording ||
    ['Starting','Stopping'].includes(state.video.message) || cameras.length < 2;
  const range=c.ae_compensation;
  const compensation=$('evComp');
  compensation.disabled=!state.device.connected || $('manualToggle').checked || !(range?.min < range?.max);
  compensation.min=range?.min ?? '';
  compensation.max=range?.max ?? '';
  $('evStep').textContent=range?.step_ev ? `${n(range.step_ev, 3)} EV per step` : 'Unavailable';
  const metering=c.metering_modes || ['camera'];
  const meteringSelector=$('meteringMode');
  if (metering.join()!==meteringOptionsKey) {
    meteringSelector.replaceChildren(...metering.map(mode=>new Option(
      {camera:'Camera default',center:'Center area',spot:'Center spot'}[mode],mode)));
    meteringOptionsKey=metering.join();
  }
  meteringSelector.disabled=!state.device.connected || $('manualToggle').checked || metering.length<2;
  const applied=state.device.telemetry?.effective?.ae_compensation;
  $('effectiveMetering').textContent=typeof applied==='number' && range?.step_ev && !compensation.disabled ?
    `Camera2 reports ${n(applied * range.step_ev, 2)} EV` : (metering.length<2 ? 'Camera-default metering only' : '');
  $('cameraDetails').textContent = c.camera_id ? JSON.stringify(c, null, 2) : 'Waiting for the phone camera';
}
function renderTelemetry(t){$('battery').textContent=t.battery_percent!=null?`Battery ${t.battery_percent}%${t.charging?' ⚡':''}`:'Battery —';$('temp').textContent=t.temperature_c!=null?`Temp ${n(t.temperature_c,1)}°C`:'Temp —';$('storage').textContent=t.free_storage_gb!=null?`Phone free ${n(t.free_storage_gb,1)} GB`:'Phone storage —'}
function drawHist(h){const c=$('histogram'),x=c.getContext('2d'),w=c.width,hh=c.height;x.clearRect(0,0,w,hh);x.strokeStyle='#21314a';x.beginPath();x.moveTo(0,hh-1);x.lineTo(w,hh-1);x.stroke();let mx=Math.max(1,...h);x.strokeStyle='#6daaff';x.lineWidth=1.5;x.beginPath();for(let i=0;i<256;i++){const xx=i/255*w,yy=hh-(h[i]||0)/mx*(hh-7);i?x.lineTo(xx,yy):x.moveTo(xx,yy)}x.stroke()}
async function poll() {
  if (exited) return;
  try {
    const next = await api('/api/state');
    serverOnline = true;
    render(next);
  } catch (error) {
    serverOnline = false;
    renderPreviewHealth();
    $('footerStatus').textContent = 'Desktop service disconnected';
  } finally { if (!exited) setTimeout(poll, 600); }
}
function renderStack(stack) {
  $('stackTools').hidden = currentMode() !== 'Live Stack';
  $('saveStack').hidden = !stack?.accepted;
  $('stackStats').textContent = stack ? `${stack.accepted} accepted · ${stack.rejected} rejected · ${stack.calibrated ? 'calibrated' : 'uncalibrated'} · ${n(stack.integration_us / 1e6, 1)} s` : '';
}
let lastStackCount=0, previewTag='', previewURL='', displayedFPS=0;
let displayedCount=0, fpsStart=performance.now(), lastDisplayed=-Infinity;
function renderPreviewHealth() {
  const connected = serverOnline && state?.device?.connected;
  const stack = currentMode()==='Live Stack' && $('showStack').checked && state?.stack?.accepted;
  const live = connected && state.metrics.live && performance.now()-lastDisplayed <= 3000;
  const visible = !!(stack || live);
  $('fpsBadge').title = state?.metrics.last_frame ?
    'Last frame received at ' + new Date(state.metrics.last_frame).toLocaleTimeString() : '';
  $('preview').classList.toggle('show', visible);
  $('streamNotice').hidden = !connected || visible;
  if (!visible) $('analysisOverlay').classList.remove('show');
  if (!live && !stack) {
    displayedFPS = 0;
    $('fpsBadge').textContent = 'Preview paused';
    $('bitrateBadge').textContent = '0.00 Mb/s';
  }
}
async function decodePreview(url) {
  const candidate=new Image();
  candidate.src=url;
  const deadline=AbortSignal.timeout(1500);
  try {
    await Promise.race([
      candidate.decode(),
      new Promise((_, reject)=>deadline.addEventListener('abort',()=>reject(deadline.reason),{once:true}))
    ]);
    return true;
  } catch {
    candidate.removeAttribute('src');
    return false;
  }
}

async function refreshPreview() {
  if (exited) return;
  const started=performance.now(), image=$('preview');
  const stacked = currentMode()==='Live Stack' && $('showStack').checked && state?.stack?.accepted;
  try {
    if (stacked) {
      if (lastStackCount!==state.stack.accepted) {
        image.src='/api/stack.png?t='+Date.now();
        if(previewURL)URL.revokeObjectURL(previewURL);
        previewURL='';previewTag='';lastDisplayed=-Infinity;
      }
      lastStackCount=state.stack.accepted;
    } else {
      lastStackCount=0;
      if (serverOnline && state?.device?.connected) {
        const headers=previewTag ? {'If-None-Match':previewTag} : {};
        const response=await fetch('/api/preview.jpg',{headers,signal:AbortSignal.timeout(2000)});
        if(response.status===200) {
          const url=URL.createObjectURL(await response.blob());
          if (await decodePreview(url)) {
            const prior=previewURL;
            previewURL=url;
            previewTag=response.headers.get('ETag') || '';
            image.src=url;
            lastDisplayed=performance.now();
            displayedCount++;
            if(prior)URL.revokeObjectURL(prior);
          } else {
            URL.revokeObjectURL(url);
            previewTag='';
          }
        } else if (response.status===204) {
          lastDisplayed=-Infinity;
          previewTag='';
        }
      } else {
        if(previewURL)URL.revokeObjectURL(previewURL);
        previewURL='';previewTag='';lastDisplayed=-Infinity;
        image.removeAttribute('src');
      }
    }
  } catch {
    previewTag='';
  } finally {
    const now=performance.now();
    if(now-fpsStart>=1000) {
      displayedFPS=displayedCount*1000/(now-fpsStart);
      fpsStart=now;displayedCount=0;
    }
    renderPreviewHealth();
    if (!exited) setTimeout(refreshPreview,Math.max(12,50-(now-started)));
  }
}
let focusEdited=false;
$('manualFocus').onchange=()=>{focusEdited=true;};
$('focus').oninput=()=>{focusEdited=true;};
const validationName = 'Validation / Daylight';
function captureProfile() {
  const target = $('profile').value;
  return target === validationName
    ? {profile:'validation',profile_name:validationName}
    : {profile:'astronomy',profile_name:target};
}
function postControls(){const processing = Object.fromEntries([...document.querySelectorAll('#processingControls select')].filter(x=>x.value!=='').map(x=>[x.name,+x.value]));const rv=$('roi').value;const rp=rv==='75%'?75:rv==='50%'?50:rv==='25%'?25:100;return api('/api/controls',{...captureProfile(),...processing,manual:$('manualToggle').checked,iso:+$('iso').value,exposure_us:+$('exposure').value,...((focusEdited && !$('manualFocus').disabled) ? {focus_distance:+$('focus').value,manual_focus:$('manualFocus').checked} : {}),ae_lock:$('aeLock').checked,awb_lock:$('awbLock').checked,ois:$('ois').value,eis:$('eis').value,roi_percent:rp,...($('evComp').disabled ? {} : {ae_compensation:+$('evComp').value}),...($('meteringMode').disabled ? {} : {metering_mode:$('meteringMode').value}),fixed_color:$('fixedColor').checked,...($('awbMode').value ? {awb_mode:+$('awbMode').value} : {})}).then(response=>{focusEdited=false;if(response.manual===true){$('iso').value=response.iso;$('exposure').value=response.exposure_us;}return response;})}
function currentMode(){return document.querySelector('.nav.active')?.dataset.mode||validationName}
function saveSession(){return api('/api/session',{target:$('targetInput').value,mode:currentMode()})}
function parseVideoMode() {
  const label = $('fpsMode').value || '';
  const match = label.match(/^(\d+)×(\d+) @ (\d+)(?:-(\d+))? fps( • HS)?$/);
  return match ? {width:+match[1],height:+match[2],fps:+(match[4] || match[3]),high_speed:!!match[5]} : null;
}
$('startVideoBtn').onclick=async()=>{try{await saveSession();await postControls();const v=parseVideoMode();if(!v)throw new Error("No video mode selected");if(v.high_speed && $('manualFocus').checked && !await confirmAction("Camera2 can override manual focus in high-speed mode. Start high-speed video?"))return;await api('/api/video/start',{...v,duration:+$('duration').value,frame_limit:+$('videoFrameLimit').value,target:$('targetInput').value,mode:currentMode(),rank_frames:$('rankFrames').checked})}catch(e){notify(e.message)}};$('stopVideoBtn').onclick=()=>api('/api/video/stop',{}).catch(e=>notify(e.message));
$('applyBtn').onclick=()=>postControls().catch(notify);$('singleBtn').onclick=async()=>{try{await saveSession();await postControls();await api('/api/capture',{maximum_jpeg:$('maximumJpeg').checked,raw:$('format').value!=='jpeg',jpeg:$('format').value!=='raw',target:$('targetInput').value,mode:currentMode(),frame_type:$('frameType').value})}catch(e){notify(e.message)}};$('startSeqBtn').onclick=async()=>{try{await saveSession();await postControls();await api('/api/sequence/start',{target:$('targetInput').value,mode:currentMode(),frame_type:$('frameType').value,count:+$('count').value,interval_ms:+$('interval').value,calibration:calibrationSelection(),raw:$('format').value!=='jpeg',jpeg:$('format').value!=='raw'})}catch(e){notify(e.message)}};$('stopSeqBtn').onclick=()=>api('/api/sequence/stop',{}).catch(notify);$('reconnectBtn').onclick=()=>api('/api/reconnect',{}).catch(notify);$('manualConnectBtn').onclick=()=>api('/api/manual-connect',{host:$('manualIp').value}).catch(e=>notify(e.message));$('sirilBtn').onclick=()=>api('/api/siril',{calibration:calibrationSelection()}).catch(e=>notify(e.message));
const workflowProfiles = {
  [validationName]:validationName, Planetary:'Jupiter', 'Lunar / Solar':'Moon',
  'Deep Sky':'Deep Sky', 'Live Stack':'Live Stack / EAA'
};
for (const button of document.querySelectorAll('.nav')) button.onclick=()=>{
  for (const nav of document.querySelectorAll('.nav')) nav.classList.toggle('active',nav===button);
  const mode=button.dataset.mode;
  $('pageTitle').textContent=mode;
  $('pageHint').textContent=hints[mode] || '';
  selectWorkflow(mode);
  if (workflowProfiles[mode] && !captureBusy(state)) {
    $('profile').value=workflowProfiles[mode];
    $('frameType').value='Light';
    applyProfile();
  }
};
[...document.querySelectorAll('.tab')].forEach(b=>b.onclick=()=>{document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.tab-body').forEach(x=>x.classList.remove('active'));b.classList.add('active');$(b.dataset.tab+'Tab').classList.add('active')});
$('targetInput').onchange=()=>saveSession().catch(notify);
function clamp(v,min,max){v=+v;if(Number.isFinite(+min))v=Math.max(v,+min);if(Number.isFinite(+max))v=Math.min(v,+max);return Math.round(v)}
let profileRequest=0;
function setDaylightDefaults(c) {
  $('manualToggle').checked=false;
  $('manualFocus').checked=false;
  focusEdited=true;
  $('iso').value=c.iso_min || 100;
  $('exposure').value=8333;
  $('ois').value=c.ois_modes?.includes(1) ? 'on' : 'off';
  $('eis').value='off';
  $('fixedColor').checked=false;
  $('aeLock').checked=false;
  $('awbLock').checked=false;
  $('awbMode').value='1';
  $('roi').selectedIndex=0;
  $('evComp').value=0;
  $('meteringMode').value='camera';
  $('format').value='jpeg';
  $('solarWarning').hidden=true;
  for(const control of document.querySelectorAll('#processingControls select')) control.value='';
}

function captureBusy(snapshot) {
  return Boolean(snapshot?.sequence.running || snapshot?.video.recording ||
    ['Starting','Stopping'].includes(snapshot?.video.message));
}

async function sendProfile() {
  if(!state?.device.connected || captureBusy(state)) return;
  const request=++profileRequest;
  try {
    if($('profile').value===validationName) {
      await api('/api/controls',{...captureProfile(),reset_profile:true});
      if(request===profileRequest) focusEdited=false;
    } else await postControls();
    if(request===profileRequest) await saveSession();
  } catch(e) { if(request===profileRequest) notify(e); }
}

function applyProfile(){const p=$('profile').value,c=state?.device?.capabilities||{};
if(p===validationName) {
  setDaylightDefaults(c);
  $('targetInput').value='Validation';
  sendProfile();
  return;
}
const preset={Jupiter:{iso:200,exp:5000,roi:'50%',duration:60,count:1000},Saturn:{iso:400,exp:10000,roi:'50%',duration:90,count:1500},Venus:{iso:100,exp:2000,roi:'50%',duration:60,count:1000},Mars:{iso:200,exp:3000,roi:'25%',duration:60,count:1200},Moon:{iso:100,exp:2000,roi:'Full frame',duration:30,count:200},Sun:{iso:50,exp:1000,roi:'Full frame',duration:20,count:200},'Deep Sky':{iso:800,exp:1000000,roi:'Full frame',duration:60,count:30},'Live Stack / EAA':{iso:800,exp:100000,roi:'Full frame',duration:60,count:30}}[p];$('targetInput').value=p==='Deep Sky'?'M42 Orion Nebula':p;if(preset){
  $('iso').value=clamp(['Sun','Moon'].includes(p) ? c.iso_min || preset.iso : preset.iso,c.iso_min,c.iso_max);
  $('exposure').value=clamp(p==='Deep Sky' ? c.exposure_us_max || preset.exp : preset.exp,c.exposure_us_min,c.exposure_us_max);
  if(c.manual_sensor) $('manualToggle').checked=true;
  if(c.min_focus_distance>0) { $('manualFocus').checked=true; $('focus').value=0; focusEdited=true; }
  $('ois').value='off'; $('eis').value='off';
  if(['Jupiter','Saturn','Mars','Venus'].includes(p)) {
    const supported=[...$('fpsMode').options].map(option=>({
      value:option.value, match:option.value.match(/^(\d+)×(\d+) @ (\d+)(?:-(\d+))? fps$/)
    })).filter(item=>item.match);
    supported.sort((a,b)=>Number(b.match[4] || b.match[3])-Number(a.match[4] || a.match[3]) ||
      Number(a.match[1])*Number(a.match[2])-Number(b.match[1])*Number(b.match[2]));
    const preferred = supported.find(item => Number(item.match[1])*Number(item.match[2])>=640*480) || supported[0];
    if(preferred)$('fpsMode').value=preferred.value;
    $('ois').value='off'; $('eis').value='off';
    for (const key of ['noise_reduction','edge','aberration']) {
      const control=$('processing-'+key);
      if(control && [...control.options].some(x=>x.value==='0')) control.value='0';
    }
  }
  if(p==='Deep Sky' && c.raw_session_available) $('format').value='raw';
  if(['Sun','Moon'].includes(p)) $('format').value='jpeg';
  $('roi').value=preset.roi;$('duration').value=preset.duration;$('count').value=preset.count;$('interval').value=p==='Deep Sky'?1000:0;
}
$('solarWarning').hidden=p!=='Sun';
sendProfile()}
$('profile').onchange=applyProfile;
[...document.querySelectorAll('[data-overlay]')].forEach(b=>b.onclick=()=>{b.classList.toggle('active');if(b.dataset.overlay==='crosshair')$('crosshair').classList.toggle('show',b.classList.contains('active'));if(b.dataset.overlay==='clip')$('clipOverlay').classList.toggle('show',b.classList.contains('active'))});$('crosshair').classList.add('show');$('clipToggle').onchange=e=>$('clipOverlay').classList.toggle('show',e.target.checked);$('redBtn').onclick=()=>{document.body.classList.toggle('red-mode');$('redBtn').classList.toggle('active')};$('fitBtn').onclick=()=>{$('preview').style.maxWidth='100%';$('preview').style.maxHeight='100%'};$('oneBtn').onclick=()=>{$('preview').style.maxWidth='none';$('preview').style.maxHeight='none'};

let overlayMode={clip:false,peaking:false};
const overlayWorker = new Worker('/assets/overlay.js');
let overlayBusy = false;
overlayWorker.onmessage = ({data}) => {
  overlayBusy = false;
  if ((!overlayMode.clip && !overlayMode.peaking) || !$('preview').classList.contains('show')) return;
  const canvas = $('analysisOverlay');
  canvas.getContext('2d').putImageData(data, 0, 0);
  canvas.classList.add('show');
};
overlayWorker.onerror = () => { overlayBusy = false; };
async function updateAnalysisOverlay() {
  const img = $('preview'), canvas = $('analysisOverlay');
  if (!img.classList.contains('show') || (!overlayMode.clip && !overlayMode.peaking)) {
    canvas.classList.remove('show');
    return;
  }
  if (overlayBusy || !img.naturalWidth) return;
  const view = $('viewer').getBoundingClientRect(), bounds = img.getBoundingClientRect();
  const width = Math.min(640, Math.max(160, Math.round(bounds.width)));
  const height = Math.min(960, Math.max(90, Math.round(width * bounds.height / Math.max(1,bounds.width))));
  canvas.width = width;
  canvas.height = height;
  Object.assign(canvas.style, {left:(bounds.left-view.left)+'px', top:(bounds.top-view.top)+'px', width:bounds.width+'px', height:bounds.height+'px'});
  overlayBusy = true;
  try {
    const bitmap = await createImageBitmap(img);
    overlayWorker.postMessage({bitmap,width,height,...overlayMode}, [bitmap]);
  } catch { overlayBusy = false; }
}

$('preview').addEventListener('load',updateAnalysisOverlay);window.addEventListener('resize',updateAnalysisOverlay);
document.addEventListener('visibilitychange', ()=>{
  if (!document.hidden && performance.now()-lastDisplayed > 3000) {
    previewTag='';
    renderPreviewHealth();
  }
});
document.querySelector('[data-overlay="clip"]').onclick=function(){this.classList.toggle('active');overlayMode.clip=this.classList.contains('active');updateAnalysisOverlay()};document.querySelector('[data-overlay="peaking"]').onclick=function(){this.classList.toggle('active');overlayMode.peaking=this.classList.contains('active');updateAnalysisOverlay()};$('clipToggle').onchange=e=>{overlayMode.clip=e.target.checked;updateAnalysisOverlay()};
function applyDisplayFilter(){const v=+$('stretch').value||0;let f='';if(v>0){const b=1+v/170,c=1+v/85;f+=`brightness(${b.toFixed(2)}) contrast(${c.toFixed(2)}) `}if(document.body.classList.contains('red-mode'))f+='sepia(1) saturate(3) hue-rotate(305deg)';$('preview').style.filter=f.trim()}
$('stretch').oninput=applyDisplayFilter;const oldRed=$('redBtn').onclick;$('redBtn').onclick=()=>{document.body.classList.toggle('red-mode');$('redBtn').classList.toggle('active');applyDisplayFilter()};
$('fpsMode').onchange = () => { $('highSpeedNote').hidden = !$('fpsMode').value.endsWith(' • HS'); };
function calibrationSelection() {
  return Object.fromEntries(['Dark','Flat','Bias'].map(kind => [kind, $('cal'+kind).value]).filter(([,id]) => id));
}

let savedSessions = [];
async function loadSessions() {
  savedSessions = await api('/api/sessions');
  for (const kind of ['Dark','Flat','Bias']) {
    const select = $('cal'+kind), previous = select.value;
    const sources = savedSessions.filter(s => s.state.frame_type === kind && s.files > 0);
    select.replaceChildren(new Option('None',''), ...sources.map(s => new Option(s.id,s.id)));
    if (sources.some(s => s.id === previous)) select.value = previous;
  }
  const select = $('savedSessions'), previous = select.value;
  select.replaceChildren(...savedSessions.map(s => new Option(s.id,s.id)));
  if (savedSessions.some(s => s.id === previous)) select.value = previous;
  renderSessionSelection();
}

function renderSessionSelection() {
  const record = savedSessions.find(s => s.id === $('savedSessions').value);
  const run = record?.state;
  $('openSavedSession').disabled = !record;
  $('savedSessionInfo').textContent = run ? `${run.message || 'Saved'} · ${run.completed} / ${run.requested} exposures · ${record.files} files` : 'No saved sessions';
  $('resumeSession').disabled = !run || run.completed >= run.requested || !state?.device.connected || state.sequence.running;
  $('resumeSession').title = $('resumeSession').disabled ? 'Select an unfinished session with the phone connected and capture idle' : '';
}
$('openSavedSession').onclick = () => api('/api/open-folder',{id:$('savedSessions').value}).catch(e => notify(e.message));
$('savedSessions').onchange = renderSessionSelection;
$('refreshSessions').onclick = () => loadSessions().catch(e => notify(e.message));
$('resumeSession').onclick = async () => {
  try {
    await api('/api/sessions/resume', {id:$('savedSessions').value});
    document.querySelector('[data-mode="Capture"]').click();
    document.querySelector('[data-tab="run"]').click();
  } catch(e) { notify(e.message); }
};

function selectWorkflow(mode) {
  $('sessionsPanel').hidden = mode !== 'Sessions';
  document.querySelector('.tabs').hidden = mode === 'Sessions';
  for (const tab of document.querySelectorAll('.tab-body')) tab.hidden = mode === 'Sessions';
  if (mode === 'Sessions' || mode === 'Live Stack') loadSessions().catch(e => notify(e.message));
  if (mode === 'Focus') document.querySelector('[data-tab="analysis"]').click();
  if (['Capture','Planetary','Lunar / Solar','Deep Sky','Live Stack'].includes(mode)) document.querySelector('[data-tab="run"]').click();
}

function renderFocus(metrics) {
  $('stellarFocus').textContent = metrics.star ? `HFR ${n(metrics.star.hfr,2)} px · FWHM ${n(metrics.star.fwhm,2)} px` : '—';
  $('stellarReason').textContent = metrics.star ? 'Background-subtracted profile · Gaussian-equivalent FWHM' : 'Pick an isolated, unsaturated star in the preview.';
  const canvas = $('focusHistory'), ctx = canvas.getContext('2d');
  const history = metrics.focus_history || [];
  ctx.clearRect(0,0,canvas.width,canvas.height);
  if (history.length < 2) return;
  const high = Math.max(...history), low = Math.min(...history);
  ctx.strokeStyle = '#6daaff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  history.forEach((value,i) => {
    const x = i/(history.length-1)*canvas.width, y = canvas.height-4-(value-low)/Math.max(.001,high-low)*(canvas.height-8);
    if (i) ctx.lineTo(x,y); else ctx.moveTo(x,y);
  });
  ctx.stroke();
}

function renderWorkflow(s) {
  const ready = s.device.connected && Boolean(s.device.capabilities.camera_id ?? s.device.camera);
  const idle = !captureBusy(s);
  for (const id of ['profile','targetInput','frameType','format']) $(id).disabled = !idle;
  for (const id of ['applyBtn','singleBtn','startSeqBtn']) {
    $(id).disabled = !ready || !idle;
    $(id).title = !ready ? 'Connect a camera' : !idle ? 'A capture run is active' : '';
  }
  if (currentMode()==='Live Stack' && $('frameType').value!=='Light') {
    $('startSeqBtn').disabled=true;
    $('startSeqBtn').title='Live stacking requires Light frames. Use Deep Sky for calibration sequences.';
  }
  $('stopSeqBtn').disabled = !s.sequence.running || s.sequence.phase==='stopping';
  $('burstBtn').disabled = !ready || !idle || !s.device.capabilities.burst_capture;
  $('burstCount').disabled = $('burstBtn').disabled;
  $('burstReason').textContent = !ready ? 'Connect a camera to check burst support.' : !s.device.capabilities.burst_capture ? 'This camera does not advertise BURST_CAPTURE.' : '';
  $('startVideoBtn').disabled ||= !idle;
  $('rankFrames').disabled = $('fpsMode').disabled || !idle;
  $('sirilBtn').disabled = !s.session.siril_path || s.session.siril_status === 'Processing' || s.session.captured_files?.length === 0;
  $('sirilBtn').title = 'Requires Siril CLI and captured light frames';
  const report = s.video.review;
  $('videoReview').textContent = report ? `${report.camera_frames_started} Camera2 starts · ${report.encoded_frames} encoded · ${report.encoder_missing_frames == null ? "encoder matching unavailable" : report.encoder_missing_frames + " unmatched encoder frames"} · ${report.cadence_gaps} missing cadence slots · ${report.camera_failures} Camera2 loss events${report.scored_frames ? ` · ${report.scored_frames} scored, ${report.best_frames.length} selected` : ''}` : s.video.recording ? `${s.video.camera_frames} Camera2 starts · ${s.video.camera_failures} loss events` : '';
  renderSessionSelection();
}

$('burstBtn').onclick = async () => {
  if (!$('burstCount').reportValidity()) return;
  try {
    await saveSession();
    await postControls();
    await api('/api/capture', {jpeg:true, burst_count:+$('burstCount').value, frame_type:$('frameType').value});
    $('burstReason').textContent = 'Burst requested. Captured files appear in the current session.';
  } catch(e) { notify(e.message); }
};
$('focusZoom').onchange = () => requestZoom($('focusZoom').checked ? 2.5 : 1);
$('showStack').onchange = () => { lastStackCount = 0; };
let zoomTimer, zoomInFlight = false, zoomQueued = null, zoomValue = 1;
function paintZoom(value) {
  zoomValue = value;
  $('displayZoom').value = value;
  $('displayZoomValue').textContent = value.toFixed(1)+'×';
  $('preview').style.setProperty('--preview-zoom', value);
  $('focusZoom').checked = value > 1;
  updateAnalysisOverlay();
}
async function sendZoom() {
  if (zoomInFlight || zoomQueued === null) return;
  const value = zoomQueued;
  zoomQueued = null;
  zoomInFlight = true;
  try {
    const reply = await api('/api/display-zoom',{ratio:value});
    if (zoomQueued === null && Number.isFinite(reply.display_zoom)) paintZoom(reply.display_zoom);
  } catch (err) {
    $('footerDetail').textContent = err.message;
  } finally {
    zoomInFlight = false;
    if (zoomQueued !== null) sendZoom();
  }
}
let focusPointArmed = false;
$('pickFocusPoint').onclick = () => {
  focusPointArmed = !focusPointArmed;
  $('pickFocusPoint').setAttribute('aria-pressed',String(focusPointArmed));
  $('viewer').style.cursor=focusPointArmed ? 'crosshair' : '';
};
$('resetFocusPoint').onclick = () => api('/api/focus-roi',null).catch(e=>notify(e.message));
$('viewer').addEventListener('click',event=>{
  if(!focusPointArmed || !state?.metrics.live || !$('preview').classList.contains('show'))return;
  const box=$('preview').getBoundingClientRect();
  if(!box.width || !box.height)return;
  const x=(event.clientX-box.left)/box.width,y=(event.clientY-box.top)/box.height;
  if(x<0||x>1||y<0||y>1)return;
  $('pickFocusPoint').click();
  api('/api/focus-roi',{x,y}).catch(e=>notify(e.message));
});
function requestZoom(value) {
  if ($('displayZoom').disabled) return;
  paintZoom(value);
  zoomQueued = value;
  if (!zoomTimer) zoomTimer = setTimeout(() => { zoomTimer = null; sendZoom(); },55);
}
$('displayZoom').oninput = () => requestZoom(+$('displayZoom').value);
$('resetZoom').onclick = () => requestZoom(1);
$('viewer').ondblclick = () => requestZoom(1);

const processingLabels = {
  noise_reduction:['Noise reduction',['Off','Fast','High quality','Minimal','Zero shutter lag']],
  edge:['Edge enhancement',['Off','Fast','High quality','Zero shutter lag']],
  hot_pixel:['Hot pixel correction',['Off','Fast','High quality']],
  tonemap:['Tone mapping',[null,'Fast','High quality']],
  aberration:['Chromatic aberration correction',['Off','Fast','High quality']]
};
let processingSignature = '';
function renderImaging(s) {
  const c=s.device.capabilities, t=s.device.telemetry, measured=t.effective || {};
  const zoom = t.display_zoom ?? c.capture_settings?.display_zoom ?? 1;
  $('displayZoom').disabled = !s.device.connected || !c.display_zoom_max;
  $('resetZoom').disabled = $('displayZoom').disabled;
  $('pickFocusPoint').disabled=!s.metrics.live;
  $('resetFocusPoint').disabled=!s.focus_point;
  const point=s.focus_point;
  $('preview').style.transformOrigin=point ? `${point.x*100}% ${point.y*100}%` : 'center';
  $('focusZoom').disabled = $('displayZoom').disabled;
  if (zoomQueued === null && !zoomInFlight && zoom !== zoomValue) paintZoom(zoom);
  const format=$('format').value;
  const maximum = c.maximum_jpeg_width > c.jpeg_width && format==='jpeg';
  $('maximumJpegRow').hidden=!maximum;
  if(!maximum)$('maximumJpeg').checked=false;
  if($('maximumJpeg').checked) {
    $('startSeqBtn').disabled=true;
    $('startSeqBtn').title='Maximum sensor JPEG is a single still';
    $('burstBtn').disabled=true;
    $('burstBtn').title='Maximum sensor JPEG is a single still';
  } else $('burstBtn').title='';
  $('maximumJpegSize').textContent=maximum ? capText('maximum_jpeg_width','maximum_jpeg_height')+' · single still; preview pauses' : '';
  const jpeg=$('maximumJpeg').checked ? capText('maximum_jpeg_width','maximum_jpeg_height') : capText('jpeg_width','jpeg_height');
  const raw=capText('raw_width','raw_height');
  const diag=t.diagnostics || c.diagnostics || {};
  const orientation = diag.preview_orientation;
  for (const id of ['previewRotation','flipPreviewX','flipPreviewY']) $(id).disabled = !s.device.connected || !orientation || orientationPending;
  if (orientation && !orientationPending) {
    $('previewRotation').value = orientation.rotation ?? 'auto';
    $('flipPreviewX').checked = orientation.flip_x;
    $('flipPreviewY').checked = orientation.flip_y;
  }
  const actualWidth=diag.preview_width || c.preview_width, actualHeight=diag.preview_height || c.preview_height;
  $('fpsBadge').textContent=`${n(s.metrics.fps)} FPS received · ${n(displayedFPS)} shown`;
  $('resBadge').textContent=actualWidth ? `Preview ${actualWidth}×${actualHeight}` : 'Preview —';
  const exposure=measured.exposure_us == null ? '' : ' · '+formatExposure(measured.exposure_us);
  const lens=diag.main_rear===true ? 'main rear' : diag.main_rear===false ? 'other rear' : 'physical lens unreported';
  $('liveDiagnostics').textContent=!s.device.connected ? 'Camera offline' :
      `${diag.profile_name || 'Camera profile unreported'} · logical ${diag.camera_id || c.camera_id || '—'} / physical ${diag.active_physical_id || '—'} · ${lens} · sensor ${diag.sensor_orientation ?? '—'}° / display ${diag.display_rotation ?? '—'}° / applied ${diag.preview_rotation ?? '—'}° · ${diag.focus || measured.focus_state || 'Focus unreported'}${exposure}`;
  $('previewQuality').disabled=!s.device.connected || s.sequence.running || s.video.recording || ['Starting','Stopping'].includes(s.video.message) || !Array.isArray(c.preview_qualities);
  if(!qualityPending && diag.preview_quality) $('previewQuality').value=diag.preview_quality;
  $('validationJpeg').disabled=!s.device.connected || s.sequence.running || s.video.recording || !c.camera_id || validationBusy;
  $('reviewJpeg').disabled=!s.last_jpeg;
  if(validationBusy && s.last_jpeg?.received && s.last_jpeg.received!==reviewPrevious && /Validation/.test(s.last_jpeg.processing)) {
    validationBusy=false;
    clearTimeout(validationTimer);
    showJpeg(s.last_jpeg);
  }
  const videoRate = s.video.fps_min && s.video.fps_min !== s.video.fps ? `${s.video.fps_min}–${s.video.fps}` : s.video.fps;
  $('captureBadge').textContent=s.video.recording && s.video.width ? `Video ${s.video.width}×${s.video.height} · ${videoRate} FPS` : format==='raw' ? 'DNG '+raw : format==='raw+jpeg' ? `JPEG ${jpeg} + DNG ${raw}` : 'JPEG '+jpeg;
  $('exposureRange').textContent=c.exposure_us_max ? `${c.exposure_us_min}–${c.exposure_us_max} µs` : 'µs';
  $('effectiveExposure').textContent=measured.iso != null && measured.exposure_us != null ?
      `Measured ISO ${measured.iso} · ${formatExposure(measured.exposure_us)}${measured.active_physical_id==null?'':` · active camera ${measured.active_physical_id}`}` : '';
  $('effectiveStabilization').textContent=[measured.ois==null?'':('OIS '+(measured.ois?'on':'off')),measured.eis==null?'':('EIS '+(measured.eis?'on':'off'))].filter(Boolean).join(' · ');
  const focusSettings=t.focus_settings || c.capture_settings || {};
  if(!focusEdited && typeof focusSettings.manual_focus==='boolean') {
    $('manualFocus').checked=focusSettings.manual_focus;
    if(Number.isFinite(focusSettings.focus_distance)) $('focus').value=focusSettings.focus_distance.toFixed(3);
  }
  const focusAvailable = Boolean(c.min_focus_distance > 0) && !$('applyBtn').disabled;
  $('focusSlider').disabled=!focusAvailable;
  $('focusSlider').max=c.min_focus_distance || 0;
  if (document.activeElement !== $('focusSlider')) $('focusSlider').value=$('focus').value;
  for (const child of $('focusSteps').children) child.disabled=!focusAvailable;
  $('fixedColorRow').hidden=!c.fixed_color_supported;
  $('fixedColor').disabled=!c.fixed_color_supported || !measured.fixed_color_ready;
  $('fixedColor').title=$('fixedColor').disabled ? 'Wait for a Camera2 auto white-balance result' : '';
  $('telescopeStabilization').hidden=$('ois').disabled && $('eis').disabled;
  const modes=c.processing_modes || {};
  const signature=JSON.stringify(modes);
  if(signature!==processingSignature) {
    $('processingControls').replaceChildren();
    for(const [key,[label,names]] of Object.entries(processingLabels)) {
      const options=(modes[key] || []).filter(value=>names[value]);
      if(!options.length)continue;
      const row=document.createElement('label');
      row.className='field';
      const title=document.createElement('span');title.textContent=label;
      const control=document.createElement('select');control.id='processing-'+key;control.name=key;
      control.append(new Option('Keep current',''),...options.map(value=>new Option(names[value],value)));
      control.value=c.capture_settings?.[key] ?? '';
      row.append(title,control);$('processingControls').append(row);
    }
    processingSignature=signature;
  }
}
let focusTimer;
function moveFocus(distance) {
  const max=state?.device?.capabilities?.min_focus_distance || 0;
  const value=Math.min(max,Math.max(0,distance));
  focusEdited=true;
  $('focus').value=value.toFixed(3);
  $('focusSlider').value=value;
  $('manualFocus').checked=true;
  clearTimeout(focusTimer);
  focusTimer=setTimeout(()=>api('/api/controls',{manual_focus:true,focus_distance:value}).catch(e=>notify(e.message)),70);
}
$('focusSlider').oninput=()=>moveFocus(+$('focusSlider').value);
$('focusMinus').onclick=()=>moveFocus(+$('focus').value-.01);
$('focusPlus').onclick=()=>moveFocus(+$('focus').value+.01);
$('focusInfinity').onclick=()=>moveFocus(0);
$('telescopeStabilization').onclick=()=>{
  $('ois').value='off';$('eis').value='off';
  api('/api/controls',{ois:false,eis:false}).catch(e=>notify(e.message));
};
function formatExposure(us) {
  const value=Number(us);
  if(!Number.isFinite(value)||value<=0)return '—';
  const seconds=value/1e6;
  const human=seconds >= .25 ? seconds.toFixed(seconds>=1?1:2)+' s' : '1/'+Math.round(1/seconds)+' s';
  return value.toLocaleString('en-US',{maximumFractionDigits:2})+' µs ('+human+')';
}
$('cameraSelect').onchange=async()=>{
  cameraSelecting=true;
  $('cameraSelect').disabled=true;
  try {
    await api('/api/controls',{camera_id:$('cameraSelect').value});
    await api('/api/focus-roi',null);
  } catch (error) { notify(error); }
  finally { cameraSelecting=false; }
};
let orientationPending=false;
async function sendOrientation() {
  if (orientationPending) return;
  orientationPending=true;
  for (const id of ['previewRotation','flipPreviewX','flipPreviewY']) $(id).disabled=true;
  try {
    const rotation = $('previewRotation').value;
    await api('/api/controls', {preview_orientation:{
      rotation: rotation === 'auto' ? null : +rotation,
      flip_x: $('flipPreviewX').checked,
      flip_y: $('flipPreviewY').checked
    }});
    await api('/api/focus-roi', null);
  } catch (error) { notify(error); }
  finally { orientationPending=false; }
}
for (const id of ['previewRotation','flipPreviewX','flipPreviewY']) $(id).onchange=sendOrientation;
let qualityPending=false;
$('previewQuality').onchange=async()=>{
  qualityPending=true;
  try {await api('/api/controls',{preview_quality:$('previewQuality').value});}
  catch(e){$('footerDetail').textContent=e.message;}
  finally{setTimeout(()=>{qualityPending=false;},900);}
};
let validationBusy=false, validationTimer, reviewPrevious;
function showJpeg(saved) {
  if(!saved)return;
  $('jpegReviewTitle').textContent=`${saved.processing} · ${saved.width}×${saved.height} · ${saved.name}`;
  $('jpegReviewImage').src='/api/last-jpeg?t='+encodeURIComponent(saved.received);
  if(!$('jpegReviewDialog').open)$('jpegReviewDialog').showModal();
}
$('reviewJpeg').onclick=()=>showJpeg(state?.last_jpeg);
$('closeJpegReview').onclick=()=>$('jpegReviewDialog').close();
$('validationJpeg').onclick=async()=>{
  if(validationBusy)return;
  validationBusy=true;
  reviewPrevious=state?.last_jpeg?.received;
  try {
    await api('/api/session',{target:'Validation',mode:validationName});
    const max=state.device.capabilities.maximum_jpeg_width > state.device.capabilities.jpeg_width;
    await api('/api/capture',{validation_jpeg:true,remote_review:true,maximum_jpeg:max,jpeg:true});
    setDaylightDefaults(state.device.capabilities);
    $('profile').value=validationName;
    $('targetInput').value='Validation';
    validationTimer=setTimeout(()=>{validationBusy=false;},60000);
  } catch(e){validationBusy=false;notify(e.message);}
};
poll();refreshPreview();
