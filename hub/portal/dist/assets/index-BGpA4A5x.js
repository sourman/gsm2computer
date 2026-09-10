const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/index-BwuROOXw.js","assets/index-DiqwetGY.css"])))=>i.map(i=>d[i]);
(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const s of document.querySelectorAll('link[rel="modulepreload"]'))r(s);new MutationObserver(s=>{for(const o of s)if(o.type==="childList")for(const i of o.addedNodes)i.tagName==="LINK"&&i.rel==="modulepreload"&&r(i)}).observe(document,{childList:!0,subtree:!0});function n(s){const o={};return s.integrity&&(o.integrity=s.integrity),s.referrerPolicy&&(o.referrerPolicy=s.referrerPolicy),s.crossOrigin==="use-credentials"?o.credentials="include":s.crossOrigin==="anonymous"?o.credentials="omit":o.credentials="same-origin",o}function r(s){if(s.ep)return;s.ep=!0;const o=n(s);fetch(s.href,o)}})();const K="modulepreload",Y=function(e){return"/portal/"+e},x={},h=function(t,n,r){let s=Promise.resolve();if(n&&n.length>0){document.getElementsByTagName("link");const i=document.querySelector("meta[property=csp-nonce]"),l=(i==null?void 0:i.nonce)||(i==null?void 0:i.getAttribute("nonce"));s=Promise.allSettled(n.map(u=>{if(u=Y(u),u in x)return;x[u]=!0;const w=u.endsWith(".css"),V=w?'[rel="stylesheet"]':"";if(document.querySelector(`link[href="${u}"]${V}`))return;const m=document.createElement("link");if(m.rel=w?"stylesheet":K,w||(m.as="script"),m.crossOrigin="",m.href=u,l&&m.setAttribute("nonce",l),document.head.appendChild(m),w)return new Promise((z,J)=>{m.addEventListener("load",z),m.addEventListener("error",()=>J(new Error(`Unable to preload CSS for ${u}`)))})}))}function o(i){const l=new Event("vite:preloadError",{cancelable:!0});if(l.payload=i,window.dispatchEvent(l),!l.defaultPrevented)throw i}return s.then(i=>{for(const l of i||[])l.status==="rejected"&&o(l.reason);return t().catch(o)})},L="".replace(/\/$/,""),$=`${L}/portal/api`;async function j(e){const t=await fetch(e);if(!t.ok){const n=await t.text();throw new Error(n||`HTTP ${t.status}`)}return t.json()}async function P(e,t){try{return{data:await j(e),mock:!1}}catch{const n=await t();return{data:n.default??n,mock:!0}}}async function M(){return P(`${L}/health`,()=>h(()=>import("./health-CSdgTVNW.js"),[]))}async function Q(e){const t=X(e);return t?{data:t,mock:!1}:{data:(await h(()=>import("./usage-CNUK0FQm.js"),[])).default,mock:!0}}function X(e){return!e||typeof e!="object"?null:e.usage&&typeof e.usage=="object"?e.usage:e.tokens&&typeof e.tokens=="object"?e.tokens:e.openclaw_tokens&&typeof e.openclaw_tokens=="object"?e.openclaw_tokens:null}async function Z(){const{data:e}=await P(`${$}/threads`,()=>h(()=>import("./threads-DsUYIr5z.js"),[]));return e}async function ee(e){try{return await j(`${$}/messages?peer=${encodeURIComponent(e)}`)}catch{return(await h(()=>import("./messages-OYSZw2Be.js"),[])).default[e]||[]}}async function B(){const{data:e,mock:t}=await P(`${$}/calls`,()=>h(()=>import("./calls-DVVb_JZE.js"),[]));return{data:e,mock:t}}async function te(e,t){try{const n=await fetch(`${$}/messages/send`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({to:e,body:t})});if(!n.ok){const r=await n.text();throw new Error(r||`HTTP ${n.status}`)}return n.json()}catch(n){if(L)throw n;return{ok:!0,mock:!0,to:e,body:t}}}function ne(e){const t=new EventSource(`${$}/events`);for(const n of["message","call","outbox"])t.addEventListener(n,r=>{try{e(n,JSON.parse(r.data))}catch(s){console.warn("sse parse",s)}});return t.onerror=()=>{},t}const D="/portal/".replace(/\/?$/,"/");function O(){return(location.hash||"").startsWith("#/")}function ae(){if(O()){const n=location.hash.slice(1);return b(n.split("?")[0])}let e=location.pathname||"/";const t=D.replace(/\/$/,"")||"";return t&&(e===t||e.startsWith(`${t}/`))&&(e=e.slice(t.length)||"/"),b(e)}function b(e){let t=e||"/";return t.startsWith("/")||(t=`/${t}`),t.length>1&&t.endsWith("/")&&(t=t.slice(0,-1)),t||"/"}function se(){const e=ae();if(e==="/messaging"||e.startsWith("/messaging/")){const t=e==="/messaging"?"/":e.slice(10);return{page:"messaging",path:e,rest:b(t)}}return e==="/routing"||e.startsWith("/routing/")?{page:"routing",path:e}:e==="/simulator"||e.startsWith("/simulator/")?{page:"simulator",path:e}:{page:"home",path:"/"}}function g(e){const t=b(e);if(O())return`#${t}`;const n=t==="/"?"":t.replace(/^\//,"");return`${D}${n}`}function E(e){const t=b(e);if(O()){location.hash=t;return}const n=g(t);if(`${location.pathname}${location.search}`===n&&!location.hash){window.dispatchEvent(new PopStateEvent("popstate"));return}history.pushState({},"",n),window.dispatchEvent(new PopStateEvent("popstate"))}function re(e){window.addEventListener("hashchange",e),window.addEventListener("popstate",e)}function oe(e){return e?'<span class="badge-mock">Local mock</span>':""}function C({page:e,mock:t,extra:n=""}){const r=(s,o,i)=>{const l=e===s?'aria-current="page"':"";return`<a class="cockpit-link${e===s?" on":""}" href="${g(o)}" ${l}>${i}</a>`};return`
    <header class="cockpit-bar">
      <span class="cockpit-mark">GSM Hub</span>
      <nav class="cockpit-nav" aria-label="Operator">
        ${r("home","/","Overview")}
        ${r("messaging","/messaging","Messaging")}
        ${r("routing","/routing","Switchboard")}
        ${r("simulator","/simulator","Simulator")}
      </nav>
      <div class="cockpit-trail">
        ${oe(t)}
        ${n}
      </div>
    </header>
  `}function S(e){if(!e)return"";const t=String(e).trim(),n=t.replace(/\D/g,"");return n==="15550100"||n==="15555550100"||n==="5550100"||n==="15555550123"?"Mock +1 555 0100":/555/.test(n)&&n.length>=7?`Mock ${t}`:t}const T=new Set;let _=!1,A=null,U={seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[]};function He(e){return T.add(e),()=>T.delete(e)}function W(){const e=ie();for(const t of T)t(e)}function ie(){return{active:_,path:A,snapshot:U}}function Ne({seated:e,echoGsm:t,deafened:n}){_||(U={seated:[...e||[]],echoGsm:!!t,deafened:[...n||[]]})}function Ie(e){return e==="loopback"?{seated:["gsm_bus"],echoGsm:!0,deafened:[],mode:"loopback",label:"GSM echo"}:{seated:["gsm_bus","openclaw_bus"],echoGsm:!1,deafened:[],mode:"openclaw",label:"Agent"}}function le(e){_=!0,A=e,W()}function ce(){_=!1,A=null,W()}function ue(){const e="".replace(/\/$/,"");return e||(typeof location<"u"?location.origin:"http://hub.mining-ling.ts.net:8787")}let p=null;function y(e,t){return`<div class="stereo-meter" id="${e}">
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
  </div>`}function de({agentOn:e=!0}={}){const t=e?"":"checked",n=e?"checked":"";return`
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
          <input id="hub-url" type="url" value="${ue()}" />
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
        ${y("meter-mic","Mic uplink")}
        ${y("meter-tone","Test tone uplink")}
        ${y("meter-downlink","Earpiece (from hub)")}
        ${y("meter-sidetone","Local sidetone")}
      </section>
      <section class="card">
        <h2>Event log</h2>
        <pre id="log"></pre>
      </section>
    </div>
  `}async function pe(e){p==null||p(),p=null;const{mountCallSimulator:t}=await h(async()=>{const{mountCallSimulator:n}=await import("./main-r-Rna2Qa.js");return{mountCallSimulator:n}},[]);p=t(e,{onCallStart:n=>le(n),onCallEnd:()=>ce()})}async function me(e){const t=await M();e.innerHTML=`
    ${C({page:"simulator",mock:t.mock})}
    ${de({agentOn:!0})}
  `,await pe(e.querySelector(".call-sim"))}function fe(){p==null||p(),p=null}async function ge(e){const t=await M(),n=t.data||{},r=await B(),s=Array.isArray(r.data)?r.data:[],o=await Q(t.mock?null:n),i=ve(s,n.last_call_tap),l=!!(t.mock||r.mock),u=n.mode||"idle";e.innerHTML=`
    ${C({page:"home",mock:l})}
    <main class="dash">
      <div class="dash-col">
        <section class="card">
          <h2>Hub status</h2>
          ${he(n)}
        </section>
        <section class="card">
          <h2>Last call</h2>
          ${i?we(i):'<p class="muted">No calls recorded.</p>'}
        </section>
      </div>
      <div class="dash-col">
        <section class="card">
          <h2>Token / usage</h2>
          ${ye(o.data)}
        </section>
      </div>
      <section class="dash-strip">
        <div class="dash-strip-preset">
          <span class="lamp-label">Routing preset</span>
          <span class="lamp-value">${d(String(u).toUpperCase())}</span>
        </div>
        <pre class="teletype dash-teletype">${d(H(u))}</pre>
        <div class="dash-strip-links">
          <a class="primary-link secondary" href="${g("/routing")}">Open switchboard</a>
          <a class="primary-link secondary" href="${g("/simulator")}">Call simulator</a>
        </div>
      </section>
      <p class="dash-tail">Last preset applied: ${d(String(u).toUpperCase())} · ${d(H(u))}${i?` · last call ${d(G(i.started||""))}`:""}</p>
    </main>
  `}function H(e){return e==="openclaw"?"openclaw_bus:monitor_* |-> gsm_bus:playback_*":e==="loopback"?"(no pw-link; uplink → gsm_bus.monitor)":e==="conference"?"conference star: GSM and OpenClaw; WA/TG listen GSM only":e==="clear"?"(buses idle)":String(e)}function he(e){const t=!!e.ok,n=e.openclaw_talk||"unknown",r=e.mode||"idle";return`<div class="lamp-row">
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
        <span class="lamp-value">${d(String(r).toUpperCase())}</span>
      </span>
    </div>
  </div>`}function ve(e,t){const n=e[0]||null,r=be(t),s=$e(t);if(!n&&!t)return null;const o=(n==null?void 0:n.started_at)||(t==null?void 0:t.ended_at)||(t==null?void 0:t.started_at)||null,i=(n==null?void 0:n.duration_sec)??r??null,l=(n==null?void 0:n.number)||s||"";return{started:o,duration:i,number:l,fromTap:!!t&&!n}}function be(e){if(!e)return null;const t=e.streams||{};let n=0;for(const s of Object.values(t)){const o=Number(s==null?void 0:s.seconds);o>n&&(n=o)}if(n>0)return Math.round(n);const r=e.mixes||{};for(const s of Object.values(r)){const o=Number(s==null?void 0:s.seconds);o>n&&(n=o)}return n>0?Math.round(n):null}function $e(e){if(!e)return"";const t=e.meta||{};return t.number||t.peer||t.from||""}function we(e){const t=e.started?G(e.started):"time unknown",n=e.duration!=null?ke(e.duration):"duration unknown",r=e.number?S(e.number):"number unknown";return`<p class="fixture-num">${d(r)}</p>
    <p class="muted last-call-meta">${d(n)}<br/>${d(t)}</p>`}function ye(e){if(!e)return'<p class="muted">No usage data.</p>';const t=e.label||"Usage",n=e.used==null?"n/a":String(e.used),r=e.limit==null?"n/a":String(e.limit),s=e.note?`<p class="muted">${d(e.note)}</p>`:"";return`<p>${d(t)}</p>
    <div class="kv">
      <span class="lamp-label">Used</span><span class="lamp-value">${d(n)}</span>
      <span class="lamp-label">Limit</span><span class="lamp-value">${d(r)}</span>
    </div>
    ${s}`}function ke(e){const t=Number(e)||0,n=Math.floor(t/60),r=t%60;return n?`${n}m ${r}s`:`${r}s`}function G(e){const t=new Date(e);if(Number.isNaN(t.getTime()))return e;const n=Date.now()-t.getTime(),r=Math.abs(n),s=new Intl.RelativeTimeFormat(void 0,{numeric:"auto"}),o=[{amount:60,unit:"second"},{amount:60,unit:"minute"},{amount:24,unit:"hour"},{amount:7,unit:"day"},{amount:4.34524,unit:"week"},{amount:12,unit:"month"},{amount:Number.POSITIVE_INFINITY,unit:"year"}];let i=r/1e3;for(const l of o){if(i<l.amount){const u=Math.round(i)*(n>=0?-1:1);return s.format(u,l.unit)}i/=l.amount}return t.toLocaleString()}function d(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}const a={tab:"messages",peer:null,threads:[],messages:[],calls:[],error:"",sending:!1,mock:!1};let N=null;function Se(e){if(e.startsWith("/thread/")){a.tab="messages",a.peer=decodeURIComponent(e.slice(8));return}if(e==="/calls"){a.tab="calls",a.peer=null;return}a.tab="messages",a.peer=null}function I(){a.peer?E(`/messaging/thread/${encodeURIComponent(a.peer)}`):a.tab==="calls"?E("/messaging/calls"):E("/messaging")}async function k(e){try{a.error="";const t=await M();if(a.mock=!!t.mock,a.tab==="calls"){const n=await B();a.calls=Array.isArray(n.data)?n.data:[],a.mock=a.mock||!!n.mock}else a.threads=await Z(),a.peer&&(a.messages=await ee(a.peer))}catch(t){a.error=t.message||String(t)}q(e)}function q(e){e.innerHTML=`
    ${C({page:"messaging",mock:a.mock})}
    <nav class="tabs">
      <a class="${a.tab==="messages"?"active":""}" data-tab="messages" href="${g("/messaging")}">Messages</a>
      <a class="${a.tab==="calls"?"active":""}" data-tab="calls" href="${g("/messaging/calls")}">Calls</a>
    </nav>
    ${a.error?`<div class="error">${c(a.error)}</div>`:""}
    <main>${a.tab==="calls"?Te():_e()}</main>
  `,e.querySelectorAll("[data-tab]").forEach(t=>{t.addEventListener("click",n=>{n.preventDefault(),a.tab=t.getAttribute("data-tab"),a.peer=null,I()})}),e.querySelectorAll("[data-peer]").forEach(t=>{t.addEventListener("click",()=>{a.peer=t.getAttribute("data-peer"),I()})}),Pe(e)}function _e(){const e=!!a.peer;return`<div class="msg-layout${e?" has-peer":""}">
    <div class="msg-list-pane">${Ee()}</div>
    <div class="msg-detail-pane">${e?Le():'<div class="empty">Select a thread. Pixel forwarder idle until a peer is chosen.</div>'}</div>
  </div>`}function Ee(){return a.threads.length?`<ul class="list">${a.threads.map(e=>`
      <li>
        <button class="row${e.peer===a.peer?" on":""}" data-peer="${Ce(e.peer)}">
          <strong>${c(S(e.peer)||e.peer)}</strong>
          <span>${c(e.lastBody||"")}</span>
          <span class="meta">${c(R(e.lastAt))}</span>
        </button>
      </li>`).join("")}</ul>`:'<div class="empty">No SMS on the wire. Pixel forwarder idle.</div>'}function Te(){return a.calls.length?`<div class="call-list">${a.calls.map(e=>{const t=e.direction!=="out",n=t?"Incoming":"Outgoing",r=e.switchboard_mode||"idle",s=e.session_id?`<div class="meta session-id">${c(e.session_id)}</div>`:"";return`<article class="call-card">
        <div class="call-card-head">
          <span class="lamp ${t?"good":"warn"}"></span>
          <h2>${c(n)}</h2>
          <span class="preset-chip">${c(r)}</span>
        </div>
        <p class="fixture-num">${c(S(e.number)||e.number||"")}</p>
        <div class="call-dur">${c(Oe(e.duration_sec))}</div>
        <div class="meta">${c(R(e.started_at))}</div>
        ${s}
      </article>`}).join("")}</div>`:'<div class="empty">Mixer idle. No GSM or simulator legs bridged.</div>'}function Le(){const e=a.messages.map(n=>{const r=n.direction==="out"?"out":"in",s=n.status&&n.status!=="received"&&n.status!=="sent"?`<span class="status-pill">${c(n.status)}</span>`:"";return`<div class="bubble ${r}"><span class="bubble-body">${c(n.body||"")}</span>${s}<time>${c(R(n.ts))}</time></div>`}).join(""),t=S(a.peer)||a.peer;return`
    <div class="thread">
      <div class="thread-head">
        <a class="back" href="${g("/messaging")}">‹ Threads</a>
        <span class="thread-peer">${c(t)}</span>
      </div>
      ${a.error?`<div class="error">${c(a.error)}</div>`:""}
      <div class="messages" id="messages">${e||'<div class="empty">No messages in this thread.</div>'}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="SMS to mock peer" required></textarea>
        <button type="submit" ${a.sending?"disabled":""}>Send</button>
      </form>
    </div>
  `}function Pe(e){const t=e.querySelector("#messages");t&&(t.scrollTop=t.scrollHeight);const n=e.querySelector("#compose");n&&n.addEventListener("submit",async r=>{r.preventDefault();const s=r.target.querySelector("textarea"),o=r.target.querySelector("button"),i=s.value.trim();if(!(!i||a.sending)){a.sending=!0,o&&(o.disabled=!0);try{await te(a.peer,i),a.sending=!1,await k(e)}catch(l){a.sending=!1,a.error=l.message||String(l),q(e)}}})}function Me(e){N||(N=ne(t=>{t==="call"&&a.tab==="calls"&&k(e),(t==="message"||t==="outbox")&&k(e)}))}function R(e){if(!e)return"";const t=new Date(e);return Number.isNaN(t.getTime())?e:t.toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})}function Oe(e){const t=Number(e)||0,n=Math.floor(t/60),r=t%60;return n?`${n}m ${r}s`:`${r}s`}function c(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function Ce(e){return c(e).replace(/'/g,"&#39;")}const f=document.getElementById("app"),Ae=Object.assign({"./routing/index.js":()=>h(()=>import("./index-BwuROOXw.js"),__vite__mapDeps([0,1]))});let v=null;async function F(){v==null||v(),v=null;const e=se();if(e.page!=="simulator"&&e.page!=="routing"&&fe(),e.page==="messaging"){Se(e.rest),Me(f),await k(f);return}if(e.page==="routing"){await Re();return}if(e.page==="simulator"){await me(f);return}await ge(f)}async function Re(){const e=Object.values(Ae);if(e.length){const t=await e[0]();if(typeof t.mount=="function"){v=await t.mount(f)||null;return}if(typeof t.render=="function"){await t.render(f);return}if(typeof t.default=="function"){await t.default(f);return}}f.innerHTML=`
    <header class="app-bar">
      <a class="back" href="${g("/")}">‹ Home</a>
      <h1>Routing</h1>
      <span></span>
    </header>
    <main class="dash">
      <p class="muted">Routing UI is not in this build yet. The hub switchboard still lives on the phone SMS commands.</p>
    </main>
  `}re(()=>{F()});const xe="/portal/sw.js".replace(/\/{2,}/g,"/");"serviceWorker"in navigator&&navigator.serviceWorker.register(xe).catch(e=>console.warn("sw",e));F();export{C as a,ie as g,He as s,Ne as u,Ie as v};
