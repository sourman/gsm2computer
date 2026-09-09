(function(){const r=document.createElement("link").relList;if(r&&r.supports&&r.supports("modulepreload"))return;for(const a of document.querySelectorAll('link[rel="modulepreload"]'))o(a);new MutationObserver(a=>{for(const i of a)if(i.type==="childList")for(const d of i.addedNodes)d.tagName==="LINK"&&d.rel==="modulepreload"&&o(d)}).observe(document,{childList:!0,subtree:!0});function s(a){const i={};return a.integrity&&(i.integrity=a.integrity),a.referrerPolicy&&(i.referrerPolicy=a.referrerPolicy),a.crossOrigin==="use-credentials"?i.credentials="include":a.crossOrigin==="anonymous"?i.credentials="omit":i.credentials="same-origin",i}function o(a){if(a.ep)return;a.ep=!0;const i=s(a);fetch(a.href,i)}})();const u="/portal/api";async function f(e){const r=await fetch(e);if(!r.ok){const s=await r.text();throw new Error(s||`HTTP ${r.status}`)}return r.json()}function b(){return f(`${u}/threads`)}function v(e){return f(`${u}/messages?peer=${encodeURIComponent(e)}`)}function $(){return f(`${u}/calls`)}async function y(e,r){const s=await fetch(`${u}/messages/send`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({to:e,body:r})});if(!s.ok){const o=await s.text();throw new Error(o||`HTTP ${s.status}`)}return s.json()}function w(e){const r=new EventSource(`${u}/events`);for(const s of["message","call","outbox"])r.addEventListener(s,o=>{try{e(s,JSON.parse(o.data))}catch(a){console.warn("sse parse",a)}});return r.onerror=()=>{},r}const l=document.getElementById("app"),t={tab:"messages",peer:null,threads:[],messages:[],calls:[],error:"",sending:!1};function g(e){if(!e)return"";const r=new Date(e);return Number.isNaN(r.getTime())?e:r.toLocaleString([],{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"})}function S(e){const r=Number(e)||0,s=Math.floor(r/60),o=r%60;return s?`${s}m ${o}s`:`${o}s`}function h(){const e=(location.hash||"").replace(/^#/,"");if(e.startsWith("/thread/")){t.tab="messages",t.peer=decodeURIComponent(e.slice(8));return}if(e==="/calls"){t.tab="calls",t.peer=null;return}t.tab="messages",t.peer=null}function p(){t.peer?location.hash=`/thread/${encodeURIComponent(t.peer)}`:t.tab==="calls"?location.hash="/calls":location.hash="/"}async function c(){try{t.error="",t.tab==="calls"?t.calls=await $():(t.threads=await b(),t.peer&&(t.messages=await v(t.peer)))}catch(e){t.error=e.message||String(e)}m()}function m(){if(t.peer){L();return}l.innerHTML=`
    <header class="app-bar">
      <h1>GSM Hub</h1>
    </header>
    <nav class="tabs">
      <button class="${t.tab==="messages"?"active":""}" data-tab="messages">Messages</button>
      <button class="${t.tab==="calls"?"active":""}" data-tab="calls">Calls</button>
    </nav>
    ${t.error?`<div class="error">${n(t.error)}</div>`:""}
    <main>${t.tab==="calls"?E():T()}</main>
  `,l.querySelectorAll("[data-tab]").forEach(e=>{e.addEventListener("click",()=>{t.tab=e.getAttribute("data-tab"),t.peer=null,p(),c()})}),l.querySelectorAll("[data-peer]").forEach(e=>{e.addEventListener("click",()=>{t.peer=e.getAttribute("data-peer"),p(),c()})})}function T(){return t.threads.length?`<ul class="list">${t.threads.map(e=>`
      <li>
        <button class="row" data-peer="${N(e.peer)}">
          <strong>${n(e.peer)}</strong>
          <span>${n(e.lastBody||"")}</span>
          <span class="meta">${n(g(e.lastAt))}</span>
        </button>
      </li>`).join("")}</ul>`:'<div class="empty">No conversations yet.<br/>Inbound SMS from the Pixel will show up here.</div>'}function E(){return t.calls.length?t.calls.map(e=>{const r=e.direction==="out"?"Outgoing":"Incoming",s=e.switchboard_mode?` · ${e.switchboard_mode}`:"",o=e.session_id?`<div class="meta">session ${n(e.session_id)}</div>`:"";return`<article class="call-card">
        <h2>${n(r)} ${n(e.number||"")}</h2>
        <div class="meta">${n(g(e.started_at))} · ${n(S(e.duration_sec))}${n(s)}</div>
        ${o}
      </article>`}).join(""):'<div class="empty">No bridged calls yet.</div>'}function L(){const e=t.messages.map(s=>{const o=s.direction==="out"?"out":"in",a=s.status&&s.status!=="received"&&s.status!=="sent"?`<span class="status-pill">${n(s.status)}</span>`:"";return`<div class="bubble ${o}">${n(s.body||"")}${a}<time>${n(g(s.ts))}</time></div>`}).join("");l.innerHTML=`
    <div class="thread">
      <header class="app-bar">
        <button class="back" id="back">‹ Threads</button>
        <h1>${n(t.peer)}</h1>
        <span></span>
      </header>
      ${t.error?`<div class="error">${n(t.error)}</div>`:""}
      <div class="messages" id="messages">${e||'<div class="empty">No messages in this thread.</div>'}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="Text message" required></textarea>
        <button type="submit" ${t.sending?"disabled":""}>Send</button>
      </form>
    </div>
  `;const r=l.querySelector("#messages");r&&(r.scrollTop=r.scrollHeight),l.querySelector("#back").addEventListener("click",()=>{t.peer=null,p(),c()}),l.querySelector("#compose").addEventListener("submit",async s=>{s.preventDefault();const o=s.target.querySelector("textarea"),a=s.target.querySelector("button"),i=o.value.trim();if(!(!i||t.sending)){t.sending=!0,a&&(a.disabled=!0);try{await y(t.peer,i),t.sending=!1,await c()}catch(d){t.sending=!1,t.error=d.message||String(d),m()}}})}function n(e){return String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function N(e){return n(e).replace(/'/g,"&#39;")}window.addEventListener("hashchange",()=>{h(),c()});"serviceWorker"in navigator&&navigator.serviceWorker.register("/portal/sw.js").catch(e=>console.warn("sw",e));h();w(e=>{e==="call"&&t.tab==="calls"&&c(),(e==="message"||e==="outbox")&&c()});c();
