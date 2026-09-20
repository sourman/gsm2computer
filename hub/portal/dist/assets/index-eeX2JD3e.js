const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/index-LSc-mvQU.js","assets/index-DiqwetGY.css"])))=>i.map(i=>d[i]);
(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const a of document.querySelectorAll('link[rel="modulepreload"]'))s(a);new MutationObserver(a=>{for(const r of a)if(r.type==="childList")for(const o of r.addedNodes)o.tagName==="LINK"&&o.rel==="modulepreload"&&s(o)}).observe(document,{childList:!0,subtree:!0});function n(a){const r={};return a.integrity&&(r.integrity=a.integrity),a.referrerPolicy&&(r.referrerPolicy=a.referrerPolicy),a.crossOrigin==="use-credentials"?r.credentials="include":a.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function s(a){if(a.ep)return;a.ep=!0;const r=n(a);fetch(a.href,r)}})();const ge="modulepreload",he=function(e){return"/portal/"+e},V={},w=function(t,n,s){let a=Promise.resolve();if(n&&n.length>0){document.getElementsByTagName("link");const o=document.querySelector("meta[property=csp-nonce]"),c=(o==null?void 0:o.nonce)||(o==null?void 0:o.getAttribute("nonce"));a=Promise.allSettled(n.map(u=>{if(u=he(u),u in V)return;V[u]=!0;const k=u.endsWith(".css"),R=k?'[rel="stylesheet"]':"";if(document.querySelector(`link[href="${u}"]${R}`))return;const y=document.createElement("link");if(y.rel=k?"stylesheet":ge,k||(y.as="script"),y.crossOrigin="",y.href=u,c&&y.setAttribute("nonce",c),document.head.appendChild(y),k)return new Promise((fe,me)=>{y.addEventListener("load",fe),y.addEventListener("error",()=>me(new Error(`Unable to preload CSS for ${u}`)))})}))}function r(o){const c=new Event("vite:preloadError",{cancelable:!0});if(c.payload=o,window.dispatchEvent(c),!c.defaultPrevented)throw o}return a.then(o=>{for(const c of o||[])c.status==="rejected"&&r(c.reason);return t().catch(r)})},ye={},be=ye||{},U=(be.VITE_HUB_ORIGIN||"").replace(/\/$/,""),E=`${U}/portal/api`;async function ee(e){const t=await fetch(e);if(!t.ok){const n=await t.text();throw new Error(n||`HTTP ${t.status}`)}return t.json()}async function q(e,t){try{return{data:await ee(e),mock:!1}}catch{const n=await t();return{data:n.default??n,mock:!0}}}async function O(){return q(`${U}/health`,()=>w(()=>import("./health-CSdgTVNW.js"),[]))}async function ve(e){const t=$e(e);return t?{data:t,mock:!1}:{data:(await w(()=>import("./usage-CNUK0FQm.js"),[])).default,mock:!0}}function $e(e){return!e||typeof e!="object"?null:e.usage&&typeof e.usage=="object"?e.usage:e.tokens&&typeof e.tokens=="object"?e.tokens:e.openclaw_tokens&&typeof e.openclaw_tokens=="object"?e.openclaw_tokens:null}async function we(){const{data:e}=await q(`${E}/threads`,()=>w(()=>import("./threads-DsUYIr5z.js"),[]));return e}async function ke(e){try{return await ee(`${E}/messages?peer=${encodeURIComponent(e)}`)}catch{return(await w(()=>import("./messages-OYSZw2Be.js"),[])).default[e]||[]}}async function te(){const{data:e,mock:t}=await q(`${E}/calls`,()=>w(()=>import("./calls-DVVb_JZE.js"),[]));return{data:e,mock:t}}function Se(e){return`${E}/calls/${encodeURIComponent(e)}/recording`}async function Ee(e,t){try{const n=await fetch(`${E}/messages/send`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({to:e,body:t})});if(!n.ok){const s=await n.text();throw new Error(s||`HTTP ${n.status}`)}return n.json()}catch(n){if(U)throw n;return{ok:!0,mock:!0,to:e,body:t}}}function _e(e){const t=new EventSource(`${E}/events`);for(const n of["message","call","outbox"])t.addEventListener(n,s=>{try{e(n,JSON.parse(s.data))}catch(a){console.warn("sse parse",a)}});return t.onerror=()=>{},t}const A={unread:{}};function ne(e=A.unread){return Object.values(e||{}).reduce((t,n)=>t+(Number(n)||0),0)}function Te(e){e&&(A.unread[e]=(A.unread[e]||0)+1)}function j(e){e&&delete A.unread[e]}function S(){const e=document.getElementById("unread-badge");if(!e)return;const t=ne();e.hidden=t===0,e.textContent=String(t)}function D(e=globalThis.isSecureContext,t=typeof Notification<"u"){return!!(e&&t)}function Ae({isSecure:e=globalThis.isSecureContext,permission:t=typeof Notification<"u"?Notification.permission:"denied"}={}){return e&&t==="granted"?"os":"in-app"}function Ce(e,{selectedPeer:t,documentHidden:n,route:s}={}){return!e||e.type!=="message"||e.direction==="out"&&e.status==="queued"?!1:!(s==="messaging"&&t&&t===e.peer&&!n)}function Y(e,t=140){const n=String(e||"").replace(/\s+/g," ").trim();return n.length<=t?n:`${n.slice(0,t-1)}…`}function Le(e){const t=e.peer||"unknown";return e.direction==="out"?{title:`SMS sent to ${t}`,body:Y(e.body)}:{title:`SMS from ${t}`,body:Y(e.body)}}async function Oe(){if(!D())throw new Error("OS notifications need a secure context (HTTPS)");return Notification.requestPermission()}async function Pe({title:e,body:t,peer:n,tag:s}){var o;const a={body:t,tag:s||`sms-${n||"desk"}`,data:{peer:n||""}},r=await((o=navigator.serviceWorker)==null?void 0:o.ready.catch(()=>null));return r&&typeof r.showNotification=="function"?(await r.showNotification(e,{...a,icon:"./icon-192.png"}),"sw"):(new Notification(e,a),"page")}function z({title:e,body:t,peer:n,onOpen:s}){const a=document.getElementById("toast-stack");if(!a)return;const r=document.createElement("button");r.type="button",r.className="toast",r.innerHTML=`<strong>${J(e)}</strong><span>${J(t||"")}</span>`;const o=()=>r.remove();r.addEventListener("click",()=>{s&&s(n),o()}),a.prepend(r),window.setTimeout(o,8e3)}function J(e){return String(e).replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;")}let K=!1;function Ie({onPermission:e}={}){const t=()=>{const n=document.getElementById("notify-btn"),s=document.getElementById("notify-hint");if(!n||!s)return;const a=D(),r=a?Notification.permission:"denied";if(!a){n.hidden=!0,s.hidden=!1,s.textContent="HTTP desk — in-app toasts only. OS alerts need HTTPS.";return}if(r==="granted"){n.hidden=!0,s.hidden=!0;return}if(r==="denied"){n.hidden=!0,s.hidden=!1,s.textContent="Alerts blocked in this browser. In-app toasts still fire.";return}n.hidden=!1,s.hidden=!0,n.textContent="Enable desk alerts"};return t(),K||(K=!0,document.addEventListener("click",async n=>{if(!n.target.closest("#notify-btn"))return;const a=document.getElementById("notify-hint");try{const r=await Oe();t(),e&&e(r)}catch(r){a&&(a.hidden=!1,a.textContent=r.message||String(r))}})),t}async function Ne(e,t){if(!Ce(e,t))return!1;const n=Le(e);if(Ae()==="os")try{return await Pe({...n,peer:e.peer,tag:e.id}),"os"}catch{return z({...n,peer:e.peer,onOpen:t.onOpen}),"in-app"}return z({...n,peer:e.peer,onOpen:t.onOpen}),"in-app"}const Me={BASE_URL:"/portal/"},Re=Me||{},se=(Re.BASE_URL||"/").replace(/\/?$/,"/");function P(){return(location.hash||"").startsWith("#/")}function He(){if(P()){const n=location.hash.slice(1);return $(n.split("?")[0])}let e=location.pathname||"/";const t=se.replace(/\/$/,"")||"";return t&&(e===t||e.startsWith(`${t}/`))&&(e=e.slice(t.length)||"/"),$(e)}function $(e){let t=e||"/";return t.startsWith("/")||(t=`/${t}`),t.length>1&&t.endsWith("/")&&(t=t.slice(0,-1)),t||"/"}function xe(e){const t=$(e);if(t==="/calls"||t.startsWith("/calls/"))return{page:"calls",path:t};if(t==="/messaging"||t.startsWith("/messaging/")){const n=t==="/messaging"?"/":t.slice(10);return{page:"messaging",path:t,rest:$(n)}}return t==="/routing"||t.startsWith("/routing/")?{page:"routing",path:t}:t==="/simulator"||t.startsWith("/simulator/")?{page:"simulator",path:t}:{page:"home",path:"/"}}function T(){return xe(He())}function v(e){const t=$(e);if(P())return`#${t}`;const n=t==="/"?"":t.replace(/^\//,"");return`${se}${n}`}function Be(e){const t=$(e);if(P()){history.replaceState(null,"",`${location.pathname}${location.search}#${t}`);return}history.replaceState({},"",v(t))}function H(e){const t=$(e);if(P()){location.hash=t;return}const n=v(t);if(`${location.pathname}${location.search}`===n&&!location.hash){window.dispatchEvent(new PopStateEvent("popstate"));return}history.pushState({},"",n),window.dispatchEvent(new PopStateEvent("popstate"))}function Ue(e){window.addEventListener("hashchange",e),window.addEventListener("popstate",e)}function qe(e){return e?'<span class="badge-mock">Local mock</span>':""}function je(){const e=D(),t=e&&typeof Notification<"u"?Notification.permission:"denied";return e?t==="granted"?`
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint" hidden></p>
    `:t==="denied"?`
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint">Alerts blocked in this browser. In-app toasts still fire.</p>
    `:`
    <button id="notify-btn" type="button" class="ghost">Enable desk alerts</button>
    <p id="notify-hint" class="hint" hidden></p>
  `:`
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint">HTTP desk — in-app toasts only. OS alerts need HTTPS.</p>
    `}function I({page:e,mock:t,extra:n=""}){const s=ne(),a=`<span id="unread-badge" class="nav-badge"${s?"":" hidden"}>${s}</span>`,r=(o,c,u)=>{const k=e===o?'aria-current="page"':"",R=o==="messaging"?a:"";return`<a class="cockpit-link${e===o?" on":""}" href="${v(c)}" ${k}>${u}${R}</a>`};return`
    <header class="cockpit-bar">
      <span class="cockpit-mark">GSM Hub</span>
      <nav class="cockpit-nav" aria-label="Operator">
        ${r("home","/","Overview")}
        ${r("messaging","/messaging","Messaging")}
        ${r("calls","/calls","Calls")}
        ${r("routing","/routing","Switchboard")}
        ${r("simulator","/simulator","Simulator")}
      </nav>
      <div class="cockpit-trail">
        ${je()}
        ${qe(t)}
        ${n}
      </div>
    </header>
  `}function N(e){if(!e)return"";const t=String(e).trim(),n=t.replace(/\D/g,"");return n==="15550100"||n==="15555550100"||n==="5550100"||n==="15555550123"?"Mock +1 555 0100":/555/.test(n)&&n.length>=7?`Mock ${t}`:t}const x=new Set;let M=!1,W=null,ae={seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[]};function Mt(e){return x.add(e),()=>x.delete(e)}function re(){const e=De();for(const t of x)t(e)}function De(){return{active:M,path:W,snapshot:ae}}function Rt({seated:e,echoGsm:t,deafened:n}){M||(ae={seated:[...e||[]],echoGsm:!!t,deafened:[...n||[]]})}function Ht(e){return e==="loopback"?{seated:["gsm_bus"],echoGsm:!0,deafened:[],mode:"loopback",label:"GSM echo"}:{seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[],mode:"openclaw",label:"Agent"}}function We(e){M=!0,W=e,re()}function Ge(){M=!1,W=null,re()}function Fe(){const e="".replace(/\/$/,"");return e||(typeof location<"u"?location.origin:"http://hub.mining-ling.ts.net:8787")}let h=null;function C(e,t){return`<div class="stereo-meter" id="${e}">
    <span class="meter-label">${t}</span>
    <div class="meter-channels">
      <div class="meter-channel" data-channel="l">
        <span class="channel-label">L</span>
        <div class="meter-track" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
          <div class="meter-fill"></div>
        </div>
      </div>
      <div class="meter-channel" data-channel="r">
        <span class="channel-label">R</span>
        <div class="meter-track" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
          <div class="meter-fill"></div>
        </div>
      </div>
    </div>
  </div>`}function Ve({agentOn:e=!0}={}){const t=e?"":"checked",n=e?"checked":"";return`
    <div class="call-sim">
      <header class="routing-head">
        <div>
          <h1>Call simulator</h1>
          <div class="sub">This is the fake handset. Same WebSocket the Pixel uses.</div>
        </div>
      </header>
      <section class="controls card">
        <label>
          Hub URL
          <input id="hub-url" type="url" value="${Fe()}" />
        </label>
        <fieldset class="call-path" id="call-path">
          <legend>What the earpiece plays</legend>
          <div class="segmented" role="radiogroup" aria-label="Call path">
            <label class="segment">
              <input type="radio" name="call-path" value="loopback" ${t} />
              <span>GSM echo</span>
            </label>
            <label class="segment">
              <input type="radio" name="call-path" value="openclaw" ${n} />
              <span>Agent</span>
            </label>
          </div>
          <p class="call-path-hint">
            GSM echo is a wire test with nobody else on the line. Agent means OpenClaw is in the room. There is no echo-through-OpenClaw.
          </p>
        </fieldset>
        <label class="checkbox">
          <input id="tone-mode" type="checkbox" />
          Test tone instead of mic (440 Hz)
        </label>
        <div class="buttons">
          <button id="start-btn" type="button" class="primary">Start call</button>
          <button id="end-btn" type="button" disabled>End call</button>
        </div>
      </section>
      <section class="stats card">
        <div><span>Status</span><strong id="status">idle</strong></div>
        <div><span>Mic streaming</span><strong id="mic-status">no</strong></div>
        <div><span>Bytes sent</span><strong id="bytes-sent">0</strong></div>
        <div><span>Bytes received</span><strong id="bytes-recv">0</strong></div>
      </section>
      <section class="audio-meters card" aria-label="Audio level meters">
        <h2>Audio levels</h2>
        ${C("meter-mic","Mic uplink")}
        ${C("meter-tone","Test tone uplink")}
        ${C("meter-downlink","Earpiece (from hub)")}
        ${C("meter-sidetone","Local sidetone")}
      </section>
      <section class="card">
        <h2>Event log</h2>
        <pre id="log"></pre>
      </section>
    </div>
  `}async function Ye(e){h==null||h(),h=null;const{mountCallSimulator:t}=await w(async()=>{const{mountCallSimulator:n}=await import("./main-r-Rna2Qa.js");return{mountCallSimulator:n}},[]);h=t(e,{onCallStart:n=>We(n),onCallEnd:()=>Ge()})}async function ze(e){const t=await O();e.innerHTML=`
    ${I({page:"simulator",mock:t.mock})}
    ${Ve({agentOn:!0})}
  `,await Ye(e.querySelector(".call-sim"))}function Je(){h==null||h(),h=null}async function Ke(e){const t=await O(),n=t.data||{},s=await te(),a=Array.isArray(s.data)?s.data:[],r=await ve(t.mock?null:n),o=Xe(a,n.last_call_tap),c=!!(t.mock||s.mock),u=n.mode||"idle";e.innerHTML=`
    ${I({page:"home",mock:c})}
    <main class="dash">
      <div class="dash-col">
        <section class="card">
          <h2>Hub status</h2>
          ${Qe(n)}
        </section>
        <section class="card">
          <h2>Last call</h2>
          ${o?tt(o):'<p class="muted">No calls recorded.</p>'}
        </section>
      </div>
      <div class="dash-col">
        <section class="card">
          <h2>Token / usage</h2>
          ${nt(r.data)}
        </section>
      </div>
      <section class="dash-strip">
        <div class="dash-strip-preset">
          <span class="lamp-label">Routing preset</span>
          <span class="lamp-value">${p(String(u).toUpperCase())}</span>
        </div>
        <pre class="teletype dash-teletype">${p(Q(u))}</pre>
        <div class="dash-strip-links">
          <a class="primary-link secondary" href="${v("/routing")}">Open switchboard</a>
          <a class="primary-link secondary" href="${v("/simulator")}">Call simulator</a>
        </div>
      </section>
      <p class="dash-tail">Last preset applied: ${p(String(u).toUpperCase())} · ${p(Q(u))}${o?` · last call ${p(ie(o.started||""))}`:""}</p>
    </main>
  `}function Q(e){return e==="openclaw"?"openclaw_bus:monitor_* |-> gsm_bus:playback_*":e==="loopback"?"(no pw-link; uplink → gsm_bus.monitor)":e==="conference"?"conference star: GSM and OpenClaw; WA/TG listen GSM only":e==="clear"?"(buses idle)":String(e)}function Qe(e){const t=!!e.ok,n=e.openclaw_talk||"unknown",s=e.mode||"idle";return`<div class="lamp-row">
    <div class="lamp-cell">
      <span class="lamp ${t?"good":"bad"}"></span>
      <span class="lamp-copy">
        <span class="lamp-label">Hub</span>
        <span class="lamp-value">${t?"OK":"Down"}</span>
      </span>
    </div>
    <div class="lamp-cell">
      <span class="lamp ${n==="webrtc-ui"?"good":"warn"}"></span>
      <span class="lamp-copy">
        <span class="lamp-label">Talk path</span>
        <span class="lamp-value">${p(n)}</span>
      </span>
    </div>
    <div class="lamp-cell">
      <span class="lamp preset-lamp"></span>
      <span class="lamp-copy">
        <span class="lamp-label">PipeWire</span>
        <span class="lamp-value">${p(String(s).toUpperCase())}</span>
      </span>
    </div>
  </div>`}function Xe(e,t){const n=e[0]||null,s=Ze(t),a=et(t);if(!n&&!t)return null;const r=(n==null?void 0:n.started_at)||(t==null?void 0:t.ended_at)||(t==null?void 0:t.started_at)||null,o=(n==null?void 0:n.duration_sec)??s??null,c=(n==null?void 0:n.number)||a||"";return{started:r,duration:o,number:c,fromTap:!!t&&!n}}function Ze(e){if(!e)return null;const t=e.streams||{};let n=0;for(const a of Object.values(t)){const r=Number(a==null?void 0:a.seconds);r>n&&(n=r)}if(n>0)return Math.round(n);const s=e.mixes||{};for(const a of Object.values(s)){const r=Number(a==null?void 0:a.seconds);r>n&&(n=r)}return n>0?Math.round(n):null}function et(e){if(!e)return"";const t=e.meta||{};return t.number||t.peer||t.from||""}function tt(e){const t=e.started?ie(e.started):"time unknown",n=e.duration!=null?st(e.duration):"duration unknown",s=e.number?N(e.number):"number unknown";return`<p class="fixture-num">${p(s)}</p>
    <p class="muted last-call-meta">${p(n)}<br/>${p(t)}</p>`}function nt(e){if(!e)return'<p class="muted">No usage data.</p>';const t=e.label||"Usage",n=e.used==null?"n/a":String(e.used),s=e.limit==null?"n/a":String(e.limit),a=e.note?`<p class="muted">${p(e.note)}</p>`:"";return`<p>${p(t)}</p>
    <div class="kv">
      <span class="lamp-label">Used</span><span class="lamp-value">${p(n)}</span>
      <span class="lamp-label">Limit</span><span class="lamp-value">${p(s)}</span>
    </div>
    ${a}`}function st(e){const t=Number(e)||0,n=Math.floor(t/60),s=t%60;return n?`${n}m ${s}s`:`${s}s`}function ie(e){const t=new Date(e);if(Number.isNaN(t.getTime()))return e;const n=Date.now()-t.getTime(),s=Math.abs(n),a=new Intl.RelativeTimeFormat(void 0,{numeric:"auto"}),r=[{amount:60,unit:"second"},{amount:60,unit:"minute"},{amount:24,unit:"hour"},{amount:7,unit:"day"},{amount:4.34524,unit:"week"},{amount:12,unit:"month"},{amount:Number.POSITIVE_INFINITY,unit:"year"}];let o=s/1e3;for(const c of r){if(o<c.amount){const u=Math.round(o)*(n>=0?-1:1);return a.format(u,c.unit)}o/=c.amount}return t.toLocaleString()}function p(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}const L="No OpenClaw recording for this call.",l={calls:[],error:"",mock:!1,playingId:null,playError:""};let d=null,B=null;function at(){l.playingId=null,l.playError="",d&&b(d)}function rt(){l.playingId=null,l.playError=L,d&&b(d)}function it(e){return B=e,e&&(e.preload="none",e.addEventListener("ended",at),e.addEventListener("error",rt),e)}function ot(){return B||(typeof Audio>"u"?null:it(new Audio))}function lt(e,t=ot()){if(l.playError="",!e)return;if(l.playingId===e&&t&&!t.paused){t.pause(),l.playingId=null,d&&b(d);return}if(!t){l.playingId=null,l.playError=L,d&&b(d);return}l.playingId&&l.playingId!==e&&t.pause();const n=Se(e);t.src=n;let s;try{s=t.play()}catch{l.playingId=null,l.playError=L,d&&b(d);return}l.playingId=e,d&&b(d),s&&typeof s.then=="function"&&s.catch(()=>{l.playingId===e&&(l.playingId=null,l.playError=L,d&&b(d))})}async function oe(e){d=e;try{l.error="";const t=await O();l.mock=!!t.mock;const n=await te();l.calls=Array.isArray(n.data)?n.data:[],l.mock=l.mock||!!n.mock}catch(t){l.error=t.message||String(t)}b(e)}function ct(e){d=e}async function ut(e,t){const n=e||d;n&&t==="call"&&await oe(n)}function b(e){e.innerHTML=`
    ${I({page:"calls",mock:l.mock})}
    ${l.error?`<div class="error">${g(l.error)}</div>`:""}
    <main>${dt()}</main>
  `,S(),pt(e)}function dt(){return l.calls.length?`<div class="call-list">${l.calls.map(e=>{const t=e.direction!=="out",n=t?"Incoming":"Outgoing",s=e.switchboard_mode||"idle",a=l.playingId===e.id,r=e.session_id?`<div class="meta session-id">${g(e.session_id)}</div>`:"",o=a?"Pause":"Play",c=a?"❚❚":"▶";return`<button type="button" class="call-card${a?" playing":""}" data-call-id="${gt(e.id)}" aria-pressed="${a?"true":"false"}" title="Play OpenClaw recording">
        <div class="call-card-head">
          <span class="lamp ${t?"good":"warn"}"></span>
          <h2>${g(n)}</h2>
          <span class="preset-chip">${g(s)}</span>
          <span class="call-play" aria-hidden="true">${c}</span>
          <span class="sr-only">${o} recording</span>
        </div>
        <p class="fixture-num">${g(N(e.number)||e.number||"")}</p>
        <div class="call-dur">${g(mt(e.duration_sec))}</div>
        <div class="meta">${g(ft(e.started_at))}</div>
        ${r}
      </button>`}).join("")}</div>${l.playError?`<div class="call-play-empty">${g(l.playError)}</div>`:""}`:'<div class="empty">Mixer idle. No GSM or simulator legs bridged.</div>'}function pt(e){e.querySelectorAll("[data-call-id]").forEach(t=>{t.addEventListener("click",()=>{const n=t.getAttribute("data-call-id");n&&lt(n)})})}function ft(e){if(!e)return"";const t=new Date(e);return Number.isNaN(t.getTime())?e:t.toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})}function mt(e){const t=Number(e)||0,n=Math.floor(t/60),s=t%60;return n?`${n}m ${s}s`:`${s}s`}function g(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function gt(e){return g(e).replace(/'/g,"&#39;")}const i={peer:null,threads:[],messages:[],error:"",sending:!1,mock:!1,suppressAutoOpen:!1};let G=null;function ht(e){return!Array.isArray(e)||e.length===0?null:[...e].sort((n,s)=>String(s.lastAt||"").localeCompare(String(n.lastAt||"")))[0].peer}function yt({peer:e,suppressAutoOpen:t,threads:n}){return e||(t?null:ht(n))}function bt(e){if(!e)return;const t=()=>{e.scrollTop=e.scrollHeight};t(),typeof requestAnimationFrame=="function"&&requestAnimationFrame(t)}function vt(e){if(e.startsWith("/thread/")){i.peer=decodeURIComponent(e.slice(8)),i.suppressAutoOpen=!1;return}i.peer=null}function $t(){return{peer:i.peer}}function le(){i.suppressAutoOpen=!1}function ce(e){e&&(i.suppressAutoOpen=!1,i.peer=e,j(e),S(),H(`/messaging/thread/${encodeURIComponent(e)}`))}function X(){i.peer?H(`/messaging/thread/${encodeURIComponent(i.peer)}`):H("/messaging")}async function F(e){G=e;try{i.error="";const t=await O();i.mock=!!t.mock,i.threads=await we();const n=yt({peer:i.peer,suppressAutoOpen:i.suppressAutoOpen,threads:i.threads});!i.peer&&n&&Be(`/messaging/thread/${encodeURIComponent(n)}`),i.peer=n,i.peer?(j(i.peer),i.messages=await ke(i.peer)):i.messages=[]}catch(t){i.error=t.message||String(t)}ue(e)}function ue(e){var t;e.innerHTML=`
    ${I({page:"messaging",mock:i.mock})}
    ${i.error?`<div class="error">${m(i.error)}</div>`:""}
    <main>${wt()}</main>
  `,S(),e.querySelectorAll("[data-peer]").forEach(n=>{n.addEventListener("click",()=>{i.suppressAutoOpen=!1,i.peer=n.getAttribute("data-peer"),j(i.peer),X()})}),(t=e.querySelector("[data-threads-back]"))==null||t.addEventListener("click",n=>{n.preventDefault(),i.suppressAutoOpen=!0,i.peer=null,X()}),Et(e)}function wt(){const e=!!i.peer;return`<div class="msg-layout${e?" has-peer":""}">
    <div class="msg-list-pane">${kt()}</div>
    <div class="msg-detail-pane">${e?St():'<div class="empty">Select a thread. Pixel forwarder idle until a peer is chosen.</div>'}</div>
  </div>`}function kt(){return i.threads.length?`<ul class="list">${i.threads.map(e=>{const t=e.peer===i.peer?" on":"",n=A.unread[e.peer]||0,s=n?`<span class="thread-unread">${n}</span>`:"";return`
      <li>
        <button class="row${t}" data-peer="${At(e.peer)}">
          <span class="row-head"><strong>${m(N(e.peer)||e.peer)}</strong>${s}</span>
          <span>${m(e.lastBody||"")}</span>
          <span class="meta">${m(de(e.lastAt))}</span>
        </button>
      </li>`}).join("")}</ul>`:'<div class="empty">No SMS on the wire. Pixel forwarder idle.</div>'}function St(){const e=i.messages.map(n=>{const s=n.direction==="out"?"out":"in",a=n.status&&n.status!=="received"&&n.status!=="sent"?`<span class="status-pill">${m(n.status)}</span>`:"";return`<div class="bubble ${s}"><span class="bubble-body">${m(n.body||"")}</span>${a}<time>${m(de(n.ts))}</time></div>`}).join(""),t=N(i.peer)||i.peer;return`
    <div class="thread">
      <div class="thread-head">
        <a class="back" data-threads-back href="${v("/messaging")}">‹ Threads</a>
        <span class="thread-peer">${m(t)}</span>
      </div>
      ${i.error?`<div class="error">${m(i.error)}</div>`:""}
      <div class="messages" id="messages">${e||'<div class="empty">No messages in this thread.</div>'}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="SMS to mock peer" required></textarea>
        <button type="submit" ${i.sending?"disabled":""}>Send</button>
      </form>
    </div>
  `}function Et(e){bt(e.querySelector("#messages"));const t=e.querySelector("#compose");t&&t.addEventListener("submit",async n=>{n.preventDefault();const s=n.target.querySelector("textarea"),a=n.target.querySelector("button"),r=s.value.trim();if(!(!r||i.sending)){i.sending=!0,a&&(a.disabled=!0);try{await Ee(i.peer,r),i.sending=!1,await F(e)}catch(o){i.sending=!1,i.error=o.message||String(o),ue(e)}}})}async function _t(e,t){const n=e||G;n&&(t==="message"||t==="outbox")&&await F(n)}function Tt(e){G=e}function de(e){if(!e)return"";const t=new Date(e);return Number.isNaN(t.getTime())?e:t.toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})}function m(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function At(e){return m(e).replace(/'/g,"&#39;")}const f=document.getElementById("app"),Ct=Object.assign({"./routing/index.js":()=>w(()=>import("./index-LSc-mvQU.js"),__vite__mapDeps([0,1]))});let _=null,Z=null;function Lt(){return T().page}async function Ot(e,t){const n=t&&typeof t=="object"?{...t,type:t.type||e}:{type:e};if(e==="message"){const s=$t(),a=await Ne(n,{selectedPeer:s.peer,documentHidden:document.hidden,route:Lt(),onOpen:o=>{o&&ce(o)}}),r=T().page==="messaging"&&s.peer===n.peer&&!document.hidden;a&&n.peer&&!r&&(Te(n.peer),S())}T().page==="messaging"?await _t(f,e):T().page==="calls"?await ut(f,e):S()}function Pt(){Z||(Z=_e((e,t)=>{Ot(e,t).catch(n=>console.warn(n))}))}function It(){const e="/portal/sw.js".replace(/\/{2,}/g,"/");"serviceWorker"in navigator&&(navigator.serviceWorker.register(e).catch(t=>console.warn("sw",t)),navigator.serviceWorker.addEventListener("message",t=>{var n,s;((n=t.data)==null?void 0:n.type)==="open-peer"&&t.data.peer?ce(t.data.peer):((s=t.data)==null?void 0:s.type)==="open-peer"&&(le(),window.location.href=v("/messaging"))}))}async function pe(){_==null||_(),_=null;const e=T();e.page!=="messaging"&&le(),e.page!=="simulator"&&e.page!=="routing"&&Je(),e.page==="messaging"?(vt(e.rest),Tt(f),await F(f)):e.page==="calls"?(ct(f),await oe(f)):e.page==="routing"?await Nt():e.page==="simulator"?await ze(f):await Ke(f),Ie(),S()}async function Nt(){const e=Object.values(Ct);if(e.length){const t=await e[0]();if(typeof t.mount=="function"){_=await t.mount(f)||null;return}if(typeof t.render=="function"){await t.render(f);return}if(typeof t.default=="function"){await t.default(f);return}}f.innerHTML=`
    <header class="app-bar">
      <a class="back" href="${v("/")}">‹ Home</a>
      <h1>Routing</h1>
      <span></span>
    </header>
    <main class="dash">
      <p class="muted">Routing UI is not in this build yet. The hub switchboard still lives on the phone SMS commands.</p>
    </main>
  `}Ue(()=>{pe()});It();Pt();pe();export{I as a,De as g,Mt as s,Rt as u,Ht as v};
