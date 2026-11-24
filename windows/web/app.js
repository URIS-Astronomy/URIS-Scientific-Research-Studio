const $=id=>document.getElementById(id);let state=null,lastFocus=0,modeSignature="",awbSignature="";
const hints={Capture:'Live telescope feed and camera control',Planetary:'Lucky-imaging workflow for Jupiter, Saturn and Mars','Lunar / Solar':'Surface-detail capture with clipping and focus aids','Deep Sky':'RAW still sequences, calibration frames and long-exposure sessions','Live Stack':'Register and stack light frames',Focus:'Large, readable focus metrics at the telescope',Sessions:'Target, files, telemetry and processing history'};
async function api(path,body){const opt=body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)};const r=await fetch(path,opt);let j={};try{j=await r.json()}catch{}if(!r.ok)throw new Error(j.error||r.statusText);return j}
function setConnected(on){['sideDot','footerDot'].forEach(id=>$(id).classList.toggle('offline',!on));$('statusPill').classList.toggle('offline',!on);$('statusText').textContent=on?'PHONE CONNECTED':'PHONE OFFLINE';$('sideStatus').textContent=on?'PHONE CONNECTED':'PHONE OFFLINE';$('offlineState').style.display=on?'none':'block';$('preview').classList.toggle('show',on);}
function n(v,d=1){return Number(v||0).toFixed(d)}
function render(s){state=s;const d=s.device,m=s.metrics,seq=s.sequence,ss=s.session;setConnected(d.connected);$('sideHost').textContent=d.connected?(d.host||'Connected'):'Auto discovery active';$('footerStatus').textContent=d.connected?'Camera online':d.status;$('footerDetail').textContent=d.last_error|| (d.connected?`Camera ${d.camera||'ready'}`:'Auto-discovery runs in the background.');$('fpsBadge').textContent=n(m.fps,1)+' FPS';$('bitrateBadge').textContent=n(m.bitrate_mbps,2)+' Mb/s';$('focusMetric').textContent=n(m.focus_score,1);$('focusBig').textContent=n(m.focus_score,1);$('focusTrend').textContent=m.focus_score>lastFocus?'▲ improving':m.focus_score<lastFocus?'▼ softer':'stable';lastFocus=m.focus_score;$('meanMetric').textContent=n(m.mean,0);$('shadowMetric').textContent=n(m.shadow_clip,2)+'%';$('highlightMetric').textContent=n(m.highlight_clip,2)+'%';$('resBadge').textContent=capText('preview_width','preview_height');drawHist(currentMode()==='Live Stack' && $('showStack').checked && s.stack?.accepted ? s.stack.histogram : m.histogram||[]);renderFocus(m);$('seqStatus').textContent=seq.message||'Idle';$('integration').textContent=seq.integration_us ? `Light integration ${n(seq.integration_us / 1e6, 2)} s` : '';$('seqCount').textContent=`${seq.completed||0} / ${seq.requested||0}`;$('seqBar').style.width=(seq.requested?Math.min(100,(seq.completed/seq.requested)*100):0)+'%';$('sirilStatus').textContent=ss.siril_status || (ss.siril_path?'Detected':'Not detected');$('fileCount').textContent=(ss.captured_files||[]).length;$('pcFolder').textContent='Session: '+(ss.current_dir||'ready').split('\\').slice(-3).join(' / ');renderTelemetry(d.telemetry||{});renderCaps(d.capabilities||{});renderStack(s.stack);renderWorkflow(s);}
function capText(a,b){const c=state?.device?.capabilities||{};return c[a]&&c[b]?`${c[a]}×${c[b]}`:'—'}
function yes(x){return x===true?'YES':x===false?'NO':'—'}
function renderCaps(c) {
  const boxes = $('capGrid').children;
  boxes[0].querySelector('b').textContent = yes(c.raw_session_available);
  boxes[1].querySelector('b').textContent = yes(c.manual_sensor);
  boxes[2].querySelector('b').textContent = yes(c.high_speed_video);
  boxes[3].querySelector('b').textContent = yes(c.burst_capture);
  const supported = {
    manualToggle: c.manual_sensor, iso: c.manual_sensor, exposure: c.manual_sensor,
    manualFocus: c.min_focus_distance > 0, focus: c.min_focus_distance > 0, aeLock: c.ae_lock_supported, awbLock: c.awb_lock_supported,
    ois: c.ois_modes?.includes(1), eis: c.eis_modes?.includes(1), roi: c.max_zoom > 1
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
    if (modes.includes(previous)) $('fpsMode').value = previous;
    modeSignature = signature;
  }
  $('fpsMode').disabled = !modes.length;
  $('startVideoBtn').disabled = !modes.length || state.video.recording || state.video.message === 'Starting';
  $('stopVideoBtn').disabled = !state.video.recording;
  $('videoState').textContent = state.video.message;
  $('highSpeedNote').hidden = !$('fpsMode').value.endsWith(' • HS');
  $('cameraDetails').textContent = c.camera_id ? JSON.stringify(c, null, 2) : 'Waiting for the phone camera';
}
function renderTelemetry(t){$('battery').textContent=t.battery_percent!=null?`Battery ${t.battery_percent}%${t.charging?' ⚡':''}`:'Battery —';$('temp').textContent=t.temperature_c!=null?`Temp ${n(t.temperature_c,1)}°C`:'Temp —';$('storage').textContent=t.free_storage_gb!=null?`Phone free ${n(t.free_storage_gb,1)} GB`:'Phone storage —'}
function drawHist(h){const c=$('histogram'),x=c.getContext('2d'),w=c.width,hh=c.height;x.clearRect(0,0,w,hh);x.strokeStyle='#21314a';x.beginPath();x.moveTo(0,hh-1);x.lineTo(w,hh-1);x.stroke();let mx=Math.max(1,...h);x.strokeStyle='#6daaff';x.lineWidth=1.5;x.beginPath();for(let i=0;i<256;i++){const xx=i/255*w,yy=hh-(h[i]||0)/mx*(hh-7);i?x.lineTo(xx,yy):x.moveTo(xx,yy)}x.stroke()}
async function poll(){try{render(await api('/api/state'))}catch{}setTimeout(poll,600)}
function renderStack(stack) {
  $('stackTools').hidden = currentMode() !== 'Live Stack';
  $('saveStack').hidden = !stack?.accepted;
  $('stackStats').textContent = stack ? `${stack.accepted} accepted · ${stack.rejected} rejected · ${stack.calibrated ? 'calibrated' : 'uncalibrated'} · ${n(stack.integration_us / 1e6, 1)} s` : '';
}
let lastStackCount = 0;
function refreshPreview() {
  const stacked = currentMode() === 'Live Stack' && $('showStack').checked && state?.stack?.accepted;
  if (stacked) {
    if (lastStackCount !== state.stack.accepted) $('preview').src = '/api/stack.png?t=' + Date.now();
    lastStackCount = state.stack.accepted;
  } else {
    lastStackCount = 0;
    if (state?.device?.connected) $('preview').src = '/api/preview.jpg?t=' + Date.now();
  }
  setTimeout(refreshPreview, 100);
}
function postControls(){const rv=$('roi').value;const rp=rv==='75%'?75:rv==='50%'?50:rv==='25%'?25:100;return api('/api/controls',{manual:$('manualToggle').checked,iso:+$('iso').value,exposure_us:+$('exposure').value,focus_distance:+$('focus').value,manual_focus:$('manualFocus').checked,ae_lock:$('aeLock').checked,awb_lock:$('awbLock').checked,ois:$('ois').value,eis:$('eis').value,roi_percent:rp,awb_mode:+$('awbMode').value||1})}
function currentMode(){return document.querySelector('.nav.active')?.dataset.mode||'Capture'}
function saveSession(){return api('/api/session',{target:$('targetInput').value,mode:currentMode()})}
function parseVideoMode(){const t=$('fpsMode').value||'';const m=t.match(/(\d+)[×x](\d+).*?(\d+)(?:-\d+)?\s*fps/i);return m?{width:+m[1],height:+m[2],fps:+m[3],high_speed:t.endsWith(' • HS')}:null}
$('startVideoBtn').onclick=async()=>{try{await saveSession();await postControls();const v=parseVideoMode();if(!v)throw new Error("No video mode selected");await api('/api/video/start',{...v,duration:+$('duration').value,frame_limit:+$('videoFrameLimit').value,target:$('targetInput').value,mode:currentMode(),rank_frames:$('rankFrames').checked})}catch(e){alert(e.message)}};$('stopVideoBtn').onclick=()=>api('/api/video/stop',{}).catch(e=>alert(e.message));
$('applyBtn').onclick=()=>postControls().catch(alert);$('singleBtn').onclick=async()=>{try{await saveSession();await postControls();await api('/api/capture',{raw:$('format').value!=='jpeg',jpeg:$('format').value!=='raw',target:$('targetInput').value,mode:currentMode(),frame_type:$('frameType').value})}catch(e){alert(e.message)}};$('startSeqBtn').onclick=async()=>{try{await saveSession();await postControls();await api('/api/sequence/start',{target:$('targetInput').value,mode:currentMode(),frame_type:$('frameType').value,count:+$('count').value,interval_ms:+$('interval').value,calibration:calibrationSelection(),raw:$('format').value!=='jpeg',jpeg:$('format').value!=='raw'})}catch(e){alert(e.message)}};$('stopSeqBtn').onclick=()=>api('/api/sequence/stop',{});$('reconnectBtn').onclick=()=>api('/api/reconnect',{});$('manualConnectBtn').onclick=()=>api('/api/manual-connect',{host:$('manualIp').value}).catch(e=>alert(e.message));$('sirilBtn').onclick=()=>api('/api/siril',{calibration:calibrationSelection()}).catch(e=>alert(e.message));
[...document.querySelectorAll('.nav')].forEach(b=>b.onclick=()=>{document.querySelectorAll('.nav').forEach(x=>x.classList.remove('active'));b.classList.add('active');$('pageTitle').textContent=b.dataset.mode;$('pageHint').textContent=hints[b.dataset.mode]||'';if(b.dataset.mode==='Planetary'){$('profile').value='Jupiter';$('count').value=1000;$('interval').value=0}else if(b.dataset.mode==='Lunar / Solar'){$('profile').value='Moon';$('count').value=100;$('interval').value=50}else if(b.dataset.mode==='Deep Sky'){$('profile').value='Deep Sky';$('count').value=30;$('interval').value=1000}selectWorkflow(b.dataset.mode);});
[...document.querySelectorAll('.tab')].forEach(b=>b.onclick=()=>{document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.tab-body').forEach(x=>x.classList.remove('active'));b.classList.add('active');$(b.dataset.tab+'Tab').classList.add('active')});
$('targetInput').onchange=saveSession;
function clamp(v,min,max){v=+v;if(Number.isFinite(+min))v=Math.max(v,+min);if(Number.isFinite(+max))v=Math.min(v,+max);return Math.round(v)}
function applyProfile(){const p=$('profile').value,c=state?.device?.capabilities||{};const preset={Jupiter:{iso:200,exp:5000,roi:'50%',duration:60,count:1000},Saturn:{iso:400,exp:10000,roi:'50%',duration:90,count:1500},Venus:{iso:100,exp:2000,roi:'50%',duration:60,count:1000},Mars:{iso:200,exp:3000,roi:'25%',duration:60,count:1200},Moon:{iso:100,exp:2000,roi:'Full frame',duration:30,count:200},Sun:{iso:50,exp:1000,roi:'Full frame',duration:20,count:200},'Deep Sky':{iso:800,exp:1000000,roi:'Full frame',duration:60,count:30}}[p];$('targetInput').value=p==='Deep Sky'?'M42 Orion Nebula':p;if(preset){$('iso').value=clamp(preset.iso,c.iso_min,c.iso_max);$('exposure').value=clamp(preset.exp,c.exposure_us_min,c.exposure_us_max);$('roi').value=preset.roi;$('duration').value=preset.duration;$('count').value=preset.count;$('interval').value=p==='Deep Sky'?1000:0}saveSession()}
$('profile').onchange=applyProfile;
[...document.querySelectorAll('[data-overlay]')].forEach(b=>b.onclick=()=>{b.classList.toggle('active');if(b.dataset.overlay==='crosshair')$('crosshair').classList.toggle('show',b.classList.contains('active'));if(b.dataset.overlay==='clip')$('clipOverlay').classList.toggle('show',b.classList.contains('active'))});$('crosshair').classList.add('show');$('clipToggle').onchange=e=>$('clipOverlay').classList.toggle('show',e.target.checked);$('redBtn').onclick=()=>{document.body.classList.toggle('red-mode');$('redBtn').classList.toggle('active')};$('fitBtn').onclick=()=>{$('preview').style.maxWidth='100%';$('preview').style.maxHeight='100%'};$('oneBtn').onclick=()=>{$('preview').style.maxWidth='none';$('preview').style.maxHeight='none'};

let overlayMode={clip:false,peaking:false};
const overlayWorker = new Worker('/assets/overlay.js');
let overlayBusy = false;
overlayWorker.onmessage = ({data}) => {
  overlayBusy = false;
  if (!overlayMode.clip && !overlayMode.peaking) return;
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
document.querySelector('[data-overlay="clip"]').onclick=function(){this.classList.toggle('active');overlayMode.clip=this.classList.contains('active');updateAnalysisOverlay()};document.querySelector('[data-overlay="peaking"]').onclick=function(){this.classList.toggle('active');overlayMode.peaking=this.classList.contains('active');updateAnalysisOverlay()};$('clipToggle').onchange=e=>{overlayMode.clip=e.target.checked;updateAnalysisOverlay()};
function applyDisplayFilter(){const v=+$('stretch').value||0;let f='';if(v>0){const b=1+v/170,c=1+v/85;f+=`brightness(${b.toFixed(2)}) contrast(${c.toFixed(2)}) `}if(document.body.classList.contains('red-mode'))f+='sepia(1) saturate(3) hue-rotate(305deg)';$('preview').style.filter=f.trim()}
$('stretch').oninput=applyDisplayFilter;const oldRed=$('redBtn').onclick;$('redBtn').onclick=()=>{document.body.classList.toggle('red-mode');$('redBtn').classList.toggle('active');applyDisplayFilter()};
$('fpsMode').onchange = () => { $('highSpeedNote').hidden = !$('fpsMode').value.endsWith(' • HS'); };
setInterval(() => api('/api/ui-heartbeat', {}).catch(() => {}), 4000);
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
  $('savedSessionInfo').textContent = run ? `${run.message || 'Saved'} · ${run.completed} / ${run.requested} exposures · ${record.files} files` : 'No saved sessions';
  $('resumeSession').disabled = !run || run.completed >= run.requested || !state?.device.connected || state.sequence.running;
  $('resumeSession').title = $('resumeSession').disabled ? 'Select an unfinished session with the phone connected and capture idle' : '';
}
$('savedSessions').onchange = renderSessionSelection;
$('refreshSessions').onclick = () => loadSessions().catch(e => alert(e.message));
$('resumeSession').onclick = async () => {
  try {
    await api('/api/sessions/resume', {id:$('savedSessions').value});
    document.querySelector('[data-mode="Capture"]').click();
    document.querySelector('[data-tab="run"]').click();
  } catch(e) { alert(e.message); }
};

function selectWorkflow(mode) {
  $('sessionsPanel').hidden = mode !== 'Sessions';
  document.querySelector('.tabs').hidden = mode === 'Sessions';
  for (const tab of document.querySelectorAll('.tab-body')) tab.hidden = mode === 'Sessions';
  if (mode === 'Sessions' || mode === 'Live Stack') loadSessions().catch(e => alert(e.message));
  if (mode === 'Focus') document.querySelector('[data-tab="analysis"]').click();
  if (['Planetary','Lunar / Solar','Deep Sky','Live Stack'].includes(mode)) document.querySelector('[data-tab="run"]').click();
}

function renderFocus(metrics) {
  $('stellarFocus').textContent = metrics.star ? `HFR ${n(metrics.star.hfr,2)} px · FWHM ${n(metrics.star.fwhm,2)} px` : '—';
  $('stellarReason').textContent = metrics.star ? 'Background-subtracted profile · Gaussian-equivalent FWHM' : 'Center an isolated, unsaturated star.';
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
  const idle = !s.sequence.running && !s.video.recording && s.video.message !== 'Starting';
  for (const id of ['applyBtn','singleBtn','startSeqBtn']) {
    $(id).disabled = !ready || !idle;
    $(id).title = !ready ? 'Connect a camera' : !idle ? 'A capture run is active' : '';
  }
  $('stopSeqBtn').disabled = !s.sequence.running;
  $('burstBtn').disabled = !ready || !idle || !s.device.capabilities.burst_capture;
  $('burstCount').disabled = $('burstBtn').disabled;
  $('burstReason').textContent = !ready ? 'Connect a camera to check burst support.' : !s.device.capabilities.burst_capture ? 'This camera does not advertise BURST_CAPTURE.' : '';
  $('startVideoBtn').disabled ||= !idle;
  $('rankFrames').disabled = $('fpsMode').disabled || !idle;
  $('sirilBtn').disabled = !s.session.siril_path || s.session.siril_status === 'Processing' || s.session.captured_files?.length === 0;
  $('sirilBtn').title = 'Requires Siril CLI and captured light frames';
  const report = s.video.review;
  $('videoReview').textContent = report ? `${report.camera_frames_started} Camera2 starts · ${report.encoded_frames} encoded · ${report.cadence_gaps} missing cadence slots · ${report.camera_failures} Camera2 loss events${report.scored_frames ? ` · ${report.scored_frames} scored, ${report.best_frames.length} selected` : ''}` : s.video.recording ? `${s.video.camera_frames} Camera2 starts · ${s.video.camera_failures} loss events` : '';
  renderSessionSelection();
}

$('burstBtn').onclick = async () => {
  if (!$('burstCount').reportValidity()) return;
  try {
    await saveSession();
    await postControls();
    await api('/api/capture', {jpeg:true, burst_count:+$('burstCount').value, frame_type:$('frameType').value});
    $('burstReason').textContent = 'Burst requested. Captured files appear in the current session.';
  } catch(e) { alert(e.message); }
};
$('focusZoom').onchange = () => $('viewer').classList.toggle('focus-zoom', $('focusZoom').checked);
$('showStack').onchange = () => { lastStackCount = 0; };
poll();refreshPreview();
