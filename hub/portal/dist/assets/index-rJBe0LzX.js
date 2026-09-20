const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/index-DMMOjZCR.js","assets/index-DiqwetGY.css"])))=>i.map(i=>d[i]);
(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const r of document.querySelectorAll('link[rel="modulepreload"]'))s(r);new MutationObserver(r=>{for(const o of r)if(o.type==="childList")for(const i of o.addedNodes)i.tagName==="LINK"&&i.rel==="modulepreload"&&s(i)}).observe(document,{childList:!0,subtree:!0});function n(r){const o={};return r.integrity&&(o.integrity=r.integrity),r.referrerPolicy&&(o.referrerPolicy=r.referrerPolicy),r.crossOrigin==="use-credentials"?o.credentials="include":r.crossOrigin==="anonymous"?o.credentials="omit":o.credentials="same-origin",o}function s(r){if(r.ep)return;r.ep=!0;const o=n(r);fetch(r.href,o)}})();const le="modulepreload",ue=function(e){return"/portal/"+e},D={},h=function(t,n,s){let r=Promise.resolve();if(n&&n.length>0){document.getElementsByTagName("link");const i=document.querySelector("meta[property=csp-nonce]"),c=(i==null?void 0:i.nonce)||(i==null?void 0:i.getAttribute("nonce"));r=Promise.allSettled(n.map(l=>{if(l=ue(l),l in D)return;D[l]=!0;const b=l.endsWith(".css"),C=b?'[rel="stylesheet"]':"";if(document.querySelector(`link[href="${l}"]${C}`))return;const g=document.createElement("link");if(g.rel=b?"stylesheet":le,b||(g.as="script"),g.crossOrigin="",g.href=l,c&&g.setAttribute("nonce",c),document.head.appendChild(g),b)return new Promise((ie,ce)=>{g.addEventListener("load",ie),g.addEventListener("error",()=>ce(new Error(`Unable to preload CSS for ${l}`)))})}))}function o(i){const c=new Event("vite:preloadError",{cancelable:!0});if(c.payload=i,window.dispatchEvent(c),!c.defaultPrevented)throw i}return r.then(i=>{for(const c of i||[])c.status==="rejected"&&o(c.reason);return t().catch(o)})},de={},pe=de||{},M=(pe.VITE_HUB_ORIGIN||"").replace(/\/$/,""),k=`${M}/portal/api`;async function K(e){const t=await fetch(e);if(!t.ok){const n=await t.text();throw new Error(n||`HTTP ${t.status}`)}return t.json()}async function I(e,t){try{return{data:await K(e),mock:!1}}catch{const n=await t();return{data:n.default??n,mock:!0}}}async function x(){return I(`${M}/health`,()=>h(()=>import("./health-CSdgTVNW.js"),[]))}async function me(e){const t=fe(e);return t?{data:t,mock:!1}:{data:(await h(()=>import("./usage-CNUK0FQm.js"),[])).default,mock:!0}}function fe(e){return!e||typeof e!="object"?null:e.usage&&typeof e.usage=="object"?e.usage:e.tokens&&typeof e.tokens=="object"?e.tokens:e.openclaw_tokens&&typeof e.openclaw_tokens=="object"?e.openclaw_tokens:null}async function ge(){const{data:e}=await I(`${k}/threads`,()=>h(()=>import("./threads-DsUYIr5z.js"),[]));return e}async function he(e){try{return await K(`${k}/messages?peer=${encodeURIComponent(e)}`)}catch{return(await h(()=>import("./messages-OYSZw2Be.js"),[])).default[e]||[]}}async function Y(){const{data:e,mock:t}=await I(`${k}/calls`,()=>h(()=>import("./calls-DVVb_JZE.js"),[]));return{data:e,mock:t}}async function be(e,t){try{const n=await fetch(`${k}/messages/send`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({to:e,body:t})});if(!n.ok){const s=await n.text();throw new Error(s||`HTTP ${n.status}`)}return n.json()}catch(n){if(M)throw n;return{ok:!0,mock:!0,to:e,body:t}}}function ve(e){const t=new EventSource(`${k}/events`);for(const n of["message","call","outbox"])t.addEventListener(n,s=>{try{e(n,JSON.parse(s.data))}catch(r){console.warn("sse parse",r)}});return t.onerror=()=>{},t}const $={unread:{}};function Q(e=$.unread){return Object.values(e||{}).reduce((t,n)=>t+(Number(n)||0),0)}function ye(e){e&&($.unread[e]=($.unread[e]||0)+1)}function H(e){e&&delete $.unread[e]}function w(){const e=document.getElementById("unread-badge");if(!e)return;const t=Q();e.hidden=t===0,e.textContent=String(t)}function R(e=globalThis.isSecureContext,t=typeof Notification<"u"){return!!(e&&t)}function $e({isSecure:e=globalThis.isSecureContext,permission:t=typeof Notification<"u"?Notification.permission:"denied"}={}){return e&&t==="granted"?"os":"in-app"}function we(e,{selectedPeer:t,documentHidden:n,route:s}={}){return!e||e.type!=="message"||e.direction==="out"&&e.status==="queued"?!1:!(s==="messaging"&&t&&t===e.peer&&!n)}function W(e,t=140){const n=String(e||"").replace(/\s+/g," ").trim();return n.length<=t?n:`${n.slice(0,t-1)}…`}function ke(e){const t=e.peer||"unknown";return e.direction==="out"?{title:`SMS sent to ${t}`,body:W(e.body)}:{title:`SMS from ${t}`,body:W(e.body)}}async function Se(){if(!R())throw new Error("OS notifications need a secure context (HTTPS)");return Notification.requestPermission()}async function _e({title:e,body:t,peer:n,tag:s}){var i;const r={body:t,tag:s||`sms-${n||"desk"}`,data:{peer:n||""}},o=await((i=navigator.serviceWorker)==null?void 0:i.ready.catch(()=>null));return o&&typeof o.showNotification=="function"?(await o.showNotification(e,{...r,icon:"./icon-192.png"}),"sw"):(new Notification(e,r),"page")}function G({title:e,body:t,peer:n,onOpen:s}){const r=document.getElementById("toast-stack");if(!r)return;const o=document.createElement("button");o.type="button",o.className="toast",o.innerHTML=`<strong>${F(e)}</strong><span>${F(t||"")}</span>`;const i=()=>o.remove();o.addEventListener("click",()=>{s&&s(n),i()}),r.prepend(o),window.setTimeout(i,8e3)}function F(e){return String(e).replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;")}let V=!1;function Ee({onPermission:e}={}){const t=()=>{const n=document.getElementById("notify-btn"),s=document.getElementById("notify-hint");if(!n||!s)return;const r=R(),o=r?Notification.permission:"denied";if(!r){n.hidden=!0,s.hidden=!1,s.textContent="HTTP desk — in-app toasts only. OS alerts need HTTPS.";return}if(o==="granted"){n.hidden=!0,s.hidden=!0;return}if(o==="denied"){n.hidden=!0,s.hidden=!1,s.textContent="Alerts blocked in this browser. In-app toasts still fire.";return}n.hidden=!1,s.hidden=!0,n.textContent="Enable desk alerts"};return t(),V||(V=!0,document.addEventListener("click",async n=>{if(!n.target.closest("#notify-btn"))return;const r=document.getElementById("notify-hint");try{const o=await Se();t(),e&&e(o)}catch(o){r&&(r.hidden=!1,r.textContent=o.message||String(o))}})),t}async function Te(e,t){if(!we(e,t))return!1;const n=ke(e);if($e()==="os")try{return await _e({...n,peer:e.peer,tag:e.id}),"os"}catch{return G({...n,peer:e.peer,onOpen:t.onOpen}),"in-app"}return G({...n,peer:e.peer,onOpen:t.onOpen}),"in-app"}const Ae={BASE_URL:"/portal/"},Oe=Ae||{},X=(Oe.BASE_URL||"/").replace(/\/?$/,"/");function A(){return(location.hash||"").startsWith("#/")}function Le(){if(A()){const n=location.hash.slice(1);return v(n.split("?")[0])}let e=location.pathname||"/";const t=X.replace(/\/$/,"")||"";return t&&(e===t||e.startsWith(`${t}/`))&&(e=e.slice(t.length)||"/"),v(e)}function v(e){let t=e||"/";return t.startsWith("/")||(t=`/${t}`),t.length>1&&t.endsWith("/")&&(t=t.slice(0,-1)),t||"/"}function E(){const e=Le();if(e==="/messaging"||e.startsWith("/messaging/")){const t=e==="/messaging"?"/":e.slice(10);return{page:"messaging",path:e,rest:v(t)}}return e==="/routing"||e.startsWith("/routing/")?{page:"routing",path:e}:e==="/simulator"||e.startsWith("/simulator/")?{page:"simulator",path:e}:{page:"home",path:"/"}}function p(e){const t=v(e);if(A())return`#${t}`;const n=t==="/"?"":t.replace(/^\//,"");return`${X}${n}`}function Ce(e){const t=v(e);if(A()){history.replaceState(null,"",`${location.pathname}${location.search}#${t}`);return}history.replaceState({},"",p(t))}function _(e){const t=v(e);if(A()){location.hash=t;return}const n=p(t);if(`${location.pathname}${location.search}`===n&&!location.hash){window.dispatchEvent(new PopStateEvent("popstate"));return}history.pushState({},"",n),window.dispatchEvent(new PopStateEvent("popstate"))}function Pe(e){window.addEventListener("hashchange",e),window.addEventListener("popstate",e)}function Ne(e){return e?'<span class="badge-mock">Local mock</span>':""}function Me(){const e=R(),t=e&&typeof Notification<"u"?Notification.permission:"denied";return e?t==="granted"?`
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
    `}function B({page:e,mock:t,extra:n=""}){const s=Q(),r=`<span id="unread-badge" class="nav-badge"${s?"":" hidden"}>${s}</span>`,o=(i,c,l)=>{const b=e===i?'aria-current="page"':"",C=i==="messaging"?r:"";return`<a class="cockpit-link${e===i?" on":""}" href="${p(c)}" ${b}>${l}${C}</a>`};return`
    <header class="cockpit-bar">
      <span class="cockpit-mark">GSM Hub</span>
      <nav class="cockpit-nav" aria-label="Operator">
        ${o("home","/","Overview")}
        ${o("messaging","/messaging","Messaging")}
        ${o("routing","/routing","Switchboard")}
        ${o("simulator","/simulator","Simulator")}
      </nav>
      <div class="cockpit-trail">
        ${Me()}
        ${Ne(t)}
        ${n}
      </div>
    </header>
  `}function O(e){if(!e)return"";const t=String(e).trim(),n=t.replace(/\D/g,"");return n==="15550100"||n==="15555550100"||n==="5550100"||n==="15555550123"?"Mock +1 555 0100":/555/.test(n)&&n.length>=7?`Mock ${t}`:t}const N=new Set;let L=!1,U=null,Z={seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[]};function ft(e){return N.add(e),()=>N.delete(e)}function ee(){const e=Ie();for(const t of N)t(e)}function Ie(){return{active:L,path:U,snapshot:Z}}function gt({seated:e,echoGsm:t,deafened:n}){L||(Z={seated:[...e||[]],echoGsm:!!t,deafened:[...n||[]]})}function ht(e){return e==="loopback"?{seated:["gsm_bus"],echoGsm:!0,deafened:[],mode:"loopback",label:"GSM echo"}:{seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[],mode:"openclaw",label:"Agent"}}function xe(e){L=!0,U=e,ee()}function He(){L=!1,U=null,ee()}function Re(){const e="".replace(/\/$/,"");return e||(typeof location<"u"?location.origin:"http://hub.mining-ling.ts.net:8787")}let m=null;function S(e,t){return`<div class="stereo-meter" id="${e}">
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
  </div>`}function Be({agentOn:e=!0}={}){const t=e?"":"checked",n=e?"checked":"";return`
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
          <input id="hub-url" type="url" value="${Re()}" />
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
        ${S("meter-mic","Mic uplink")}
        ${S("meter-tone","Test tone uplink")}
        ${S("meter-downlink","Earpiece (from hub)")}
        ${S("meter-sidetone","Local sidetone")}
      </section>
      <section class="card">
        <h2>Event log</h2>
        <pre id="log"></pre>
      </section>
    </div>
  `}async function Ue(e){m==null||m(),m=null;const{mountCallSimulator:t}=await h(async()=>{const{mountCallSimulator:n}=await import("./main-r-Rna2Qa.js");return{mountCallSimulator:n}},[]);m=t(e,{onCallStart:n=>xe(n),onCallEnd:()=>He()})}async function je(e){const t=await x();e.innerHTML=`
    ${B({page:"simulator",mock:t.mock})}
    ${Be({agentOn:!0})}
  `,await Ue(e.querySelector(".call-sim"))}function qe(){m==null||m(),m=null}async function De(e){const t=await x(),n=t.data||{},s=await Y(),r=Array.isArray(s.data)?s.data:[],o=await me(t.mock?null:n),i=Ge(r,n.last_call_tap),c=!!(t.mock||s.mock),l=n.mode||"idle";e.innerHTML=`
    ${B({page:"home",mock:c})}
    <main class="dash">
      <div class="dash-col">
        <section class="card">
          <h2>Hub status</h2>
          ${We(n)}
        </section>
        <section class="card">
          <h2>Last call</h2>
          ${i?ze(i):'<p class="muted">No calls recorded.</p>'}
        </section>
      </div>
      <div class="dash-col">
        <section class="card">
          <h2>Token / usage</h2>
          ${Je(o.data)}
        </section>
      </div>
      <section class="dash-strip">
        <div class="dash-strip-preset">
          <span class="lamp-label">Routing preset</span>
          <span class="lamp-value">${d(String(l).toUpperCase())}</span>
        </div>
        <pre class="teletype dash-teletype">${d(z(l))}</pre>
        <div class="dash-strip-links">
          <a class="primary-link secondary" href="${p("/routing")}">Open switchboard</a>
          <a class="primary-link secondary" href="${p("/simulator")}">Call simulator</a>
        </div>
      </section>
      <p class="dash-tail">Last preset applied: ${d(String(l).toUpperCase())} · ${d(z(l))}${i?` · last call ${d(te(i.started||""))}`:""}</p>
    </main>
  `}function z(e){return e==="openclaw"?"openclaw_bus:monitor_* |-> gsm_bus:playback_*":e==="loopback"?"(no pw-link; uplink → gsm_bus.monitor)":e==="conference"?"conference star: GSM and OpenClaw; WA/TG listen GSM only":e==="clear"?"(buses idle)":String(e)}function We(e){const t=!!e.ok,n=e.openclaw_talk||"unknown",s=e.mode||"idle";return`<div class="lamp-row">
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
        <span class="lamp-value">${d(n)}</span>
      </span>
    </div>
    <div class="lamp-cell">
      <span class="lamp preset-lamp"></span>
      <span class="lamp-copy">
        <span class="lamp-label">PipeWire</span>
        <span class="lamp-value">${d(String(s).toUpperCase())}</span>
      </span>
    </div>
  </div>`}function Ge(e,t){const n=e[0]||null,s=Fe(t),r=Ve(t);if(!n&&!t)return null;const o=(n==null?void 0:n.started_at)||(t==null?void 0:t.ended_at)||(t==null?void 0:t.started_at)||null,i=(n==null?void 0:n.duration_sec)??s??null,c=(n==null?void 0:n.number)||r||"";return{started:o,duration:i,number:c,fromTap:!!t&&!n}}function Fe(e){if(!e)return null;const t=e.streams||{};let n=0;for(const r of Object.values(t)){const o=Number(r==null?void 0:r.seconds);o>n&&(n=o)}if(n>0)return Math.round(n);const s=e.mixes||{};for(const r of Object.values(s)){const o=Number(r==null?void 0:r.seconds);o>n&&(n=o)}return n>0?Math.round(n):null}function Ve(e){if(!e)return"";const t=e.meta||{};return t.number||t.peer||t.from||""}function ze(e){const t=e.started?te(e.started):"time unknown",n=e.duration!=null?Ke(e.duration):"duration unknown",s=e.number?O(e.number):"number unknown";return`<p class="fixture-num">${d(s)}</p>
    <p class="muted last-call-meta">${d(n)}<br/>${d(t)}</p>`}function Je(e){if(!e)return'<p class="muted">No usage data.</p>';const t=e.label||"Usage",n=e.used==null?"n/a":String(e.used),s=e.limit==null?"n/a":String(e.limit),r=e.note?`<p class="muted">${d(e.note)}</p>`:"";return`<p>${d(t)}</p>
    <div class="kv">
      <span class="lamp-label">Used</span><span class="lamp-value">${d(n)}</span>
      <span class="lamp-label">Limit</span><span class="lamp-value">${d(s)}</span>
    </div>
    ${r}`}function Ke(e){const t=Number(e)||0,n=Math.floor(t/60),s=t%60;return n?`${n}m ${s}s`:`${s}s`}function te(e){const t=new Date(e);if(Number.isNaN(t.getTime()))return e;const n=Date.now()-t.getTime(),s=Math.abs(n),r=new Intl.RelativeTimeFormat(void 0,{numeric:"auto"}),o=[{amount:60,unit:"second"},{amount:60,unit:"minute"},{amount:24,unit:"hour"},{amount:7,unit:"day"},{amount:4.34524,unit:"week"},{amount:12,unit:"month"},{amount:Number.POSITIVE_INFINITY,unit:"year"}];let i=s/1e3;for(const c of o){if(i<c.amount){const l=Math.round(i)*(n>=0?-1:1);return r.format(l,c.unit)}i/=c.amount}return t.toLocaleString()}function d(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}const a={tab:"messages",peer:null,threads:[],messages:[],calls:[],error:"",sending:!1,mock:!1,suppressAutoOpen:!1};let j=null;function Ye(e){return!Array.isArray(e)||e.length===0?null:[...e].sort((n,s)=>String(s.lastAt||"").localeCompare(String(n.lastAt||"")))[0].peer}function Qe(e){if(!e)return;const t=()=>{e.scrollTop=e.scrollHeight};t(),typeof requestAnimationFrame=="function"&&requestAnimationFrame(t)}function Xe(e){if(e.startsWith("/thread/")){a.tab="messages",a.peer=decodeURIComponent(e.slice(8)),a.suppressAutoOpen=!1;return}if(e==="/calls"){a.tab="calls",a.peer=null;return}a.tab="messages",a.peer=null}function ne(){return{peer:a.peer,tab:a.tab}}function se(){a.suppressAutoOpen=!1}function ae(e){e&&(a.suppressAutoOpen=!1,a.tab="messages",a.peer=e,H(e),w(),_(`/messaging/thread/${encodeURIComponent(e)}`))}function P(){a.peer?_(`/messaging/thread/${encodeURIComponent(a.peer)}`):a.tab==="calls"?_("/messaging/calls"):_("/messaging")}async function T(e){j=e;try{a.error="";const t=await x();if(a.mock=!!t.mock,a.tab==="calls"){const n=await Y();a.calls=Array.isArray(n.data)?n.data:[],a.mock=a.mock||!!n.mock}else{if(a.threads=await ge(),!a.peer&&!a.suppressAutoOpen){const n=Ye(a.threads);n&&(a.peer=n,Ce(`/messaging/thread/${encodeURIComponent(n)}`))}a.peer?(H(a.peer),a.messages=await he(a.peer)):a.messages=[]}}catch(t){a.error=t.message||String(t)}re(e)}function re(e){var t;e.innerHTML=`
    ${B({page:"messaging",mock:a.mock})}
    <nav class="tabs">
      <a class="${a.tab==="messages"?"active":""}" data-tab="messages" href="${p("/messaging")}">Messages</a>
      <a class="${a.tab==="calls"?"active":""}" data-tab="calls" href="${p("/messaging/calls")}">Calls</a>
    </nav>
    ${a.error?`<div class="error">${u(a.error)}</div>`:""}
    <main>${a.tab==="calls"?tt():Ze()}</main>
  `,w(),e.querySelectorAll("[data-tab]").forEach(n=>{n.addEventListener("click",s=>{s.preventDefault(),a.tab=n.getAttribute("data-tab"),a.peer=null,a.suppressAutoOpen=a.tab!=="messages",P()})}),e.querySelectorAll("[data-peer]").forEach(n=>{n.addEventListener("click",()=>{a.suppressAutoOpen=!1,a.peer=n.getAttribute("data-peer"),H(a.peer),P()})}),(t=e.querySelector("[data-threads-back]"))==null||t.addEventListener("click",n=>{n.preventDefault(),a.suppressAutoOpen=!0,a.peer=null,P()}),st(e)}function Ze(){const e=!!a.peer;return`<div class="msg-layout${e?" has-peer":""}">
    <div class="msg-list-pane">${et()}</div>
    <div class="msg-detail-pane">${e?nt():'<div class="empty">Select a thread. Pixel forwarder idle until a peer is chosen.</div>'}</div>
  </div>`}function et(){return a.threads.length?`<ul class="list">${a.threads.map(e=>{const t=e.peer===a.peer?" on":"",n=$.unread[e.peer]||0,s=n?`<span class="thread-unread">${n}</span>`:"";return`
      <li>
        <button class="row${t}" data-peer="${it(e.peer)}">
          <span class="row-head"><strong>${u(O(e.peer)||e.peer)}</strong>${s}</span>
          <span>${u(e.lastBody||"")}</span>
          <span class="meta">${u(q(e.lastAt))}</span>
        </button>
      </li>`}).join("")}</ul>`:'<div class="empty">No SMS on the wire. Pixel forwarder idle.</div>'}function tt(){return a.calls.length?`<div class="call-list">${a.calls.map(e=>{const t=e.direction!=="out",n=t?"Incoming":"Outgoing",s=e.switchboard_mode||"idle",r=e.session_id?`<div class="meta session-id">${u(e.session_id)}</div>`:"";return`<article class="call-card">
        <div class="call-card-head">
          <span class="lamp ${t?"good":"warn"}"></span>
          <h2>${u(n)}</h2>
          <span class="preset-chip">${u(s)}</span>
        </div>
        <p class="fixture-num">${u(O(e.number)||e.number||"")}</p>
        <div class="call-dur">${u(ot(e.duration_sec))}</div>
        <div class="meta">${u(q(e.started_at))}</div>
        ${r}
      </article>`}).join("")}</div>`:'<div class="empty">Mixer idle. No GSM or simulator legs bridged.</div>'}function nt(){const e=a.messages.map(n=>{const s=n.direction==="out"?"out":"in",r=n.status&&n.status!=="received"&&n.status!=="sent"?`<span class="status-pill">${u(n.status)}</span>`:"";return`<div class="bubble ${s}"><span class="bubble-body">${u(n.body||"")}</span>${r}<time>${u(q(n.ts))}</time></div>`}).join(""),t=O(a.peer)||a.peer;return`
    <div class="thread">
      <div class="thread-head">
        <a class="back" data-threads-back href="${p("/messaging")}">‹ Threads</a>
        <span class="thread-peer">${u(t)}</span>
      </div>
      ${a.error?`<div class="error">${u(a.error)}</div>`:""}
      <div class="messages" id="messages">${e||'<div class="empty">No messages in this thread.</div>'}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="SMS to mock peer" required></textarea>
        <button type="submit" ${a.sending?"disabled":""}>Send</button>
      </form>
    </div>
  `}function st(e){Qe(e.querySelector("#messages"));const t=e.querySelector("#compose");t&&t.addEventListener("submit",async n=>{n.preventDefault();const s=n.target.querySelector("textarea"),r=n.target.querySelector("button"),o=s.value.trim();if(!(!o||a.sending)){a.sending=!0,r&&(r.disabled=!0);try{await be(a.peer,o),a.sending=!1,await T(e)}catch(i){a.sending=!1,a.error=i.message||String(i),re(e)}}})}async function at(e,t){const n=e||j;n&&(t==="call"&&a.tab==="calls"&&await T(n),(t==="message"||t==="outbox")&&await T(n))}function rt(e){j=e}function q(e){if(!e)return"";const t=new Date(e);return Number.isNaN(t.getTime())?e:t.toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})}function ot(e){const t=Number(e)||0,n=Math.floor(t/60),s=t%60;return n?`${n}m ${s}s`:`${s}s`}function u(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function it(e){return u(e).replace(/'/g,"&#39;")}const f=document.getElementById("app"),ct=Object.assign({"./routing/index.js":()=>h(()=>import("./index-DMMOjZCR.js"),__vite__mapDeps([0,1]))});let y=null,J=null;function lt(){const e=E(),t=ne();return e.page==="messaging"&&t.tab==="messages"?"messaging":e.page}async function ut(e,t){const n=t&&typeof t=="object"?{...t,type:t.type||e}:{type:e};if(e==="message"){const s=ne(),r=await Te(n,{selectedPeer:s.peer,documentHidden:document.hidden,route:lt(),onOpen:i=>{i&&ae(i)}}),o=E().page==="messaging"&&s.tab==="messages"&&s.peer===n.peer&&!document.hidden;r&&n.peer&&!o&&(ye(n.peer),w())}E().page==="messaging"?await at(f,e):w()}function dt(){J||(J=ve((e,t)=>{ut(e,t).catch(n=>console.warn(n))}))}function pt(){const e="/portal/sw.js".replace(/\/{2,}/g,"/");"serviceWorker"in navigator&&(navigator.serviceWorker.register(e).catch(t=>console.warn("sw",t)),navigator.serviceWorker.addEventListener("message",t=>{var n,s;((n=t.data)==null?void 0:n.type)==="open-peer"&&t.data.peer?ae(t.data.peer):((s=t.data)==null?void 0:s.type)==="open-peer"&&(se(),window.location.href=p("/messaging"))}))}async function oe(){y==null||y(),y=null;const e=E();e.page!=="messaging"&&se(),e.page!=="simulator"&&e.page!=="routing"&&qe(),e.page==="messaging"?(Xe(e.rest),rt(f),await T(f)):e.page==="routing"?await mt():e.page==="simulator"?await je(f):await De(f),Ee(),w()}async function mt(){const e=Object.values(ct);if(e.length){const t=await e[0]();if(typeof t.mount=="function"){y=await t.mount(f)||null;return}if(typeof t.render=="function"){await t.render(f);return}if(typeof t.default=="function"){await t.default(f);return}}f.innerHTML=`
    <header class="app-bar">
      <a class="back" href="${p("/")}">‹ Home</a>
      <h1>Routing</h1>
      <span></span>
    </header>
    <main class="dash">
      <p class="muted">Routing UI is not in this build yet. The hub switchboard still lives on the phone SMS commands.</p>
    </main>
  `}Pe(()=>{oe()});pt();dt();oe();export{B as a,Ie as g,ft as s,gt as u,ht as v};
