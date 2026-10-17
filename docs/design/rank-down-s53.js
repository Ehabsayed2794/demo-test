(function(){
/* Ladder: real values — RANKING_RULES_AND_VERIFIED_DATA.md §1. No tier colours (OPEN_DECISIONS #1). */
var TIERS=[['Bronze','مبتدئ'],['Silver','لاعب'],['Gold','معلم'],['Platinum','وزير'],['Diamond','أمير'],['Royal','سلطان']];
var RP=[0,75,150,250,350,450,575,700,825,1000,1200,1400,1600,1800,2000,2250,2500,2750,3000];
var ROM={1:'I',2:'II',3:'III'};
var fmt=function(n){return n.toLocaleString('en-US')};
function rung(i){var t=TIERS[Math.floor(i/3)];return{i:i,en:t[0],ar:t[1],div:ROM[3-i%3],rp:RP[i]}}
var label=function(r){return r.en+' '+r.div+' · '+r.ar};
/* Declared binding — server-owned; the client never computes RP. from/to index the ladder above (0–18). rp values are SAMPLES.
   A demotion is one rung: to === from-1, and RP[to] <= rp < RP[from]. Floor (Bronze III, index 0) and King are never `from` here. */
window.rankDownBinding={source:'sample',seasonId:'sample',variants:[
{id:'v1',kind:'division-step',from:8,to:7,rp:812},
{id:'v2',kind:'tier-demotion',from:9,to:8,rp:988}]};
window.rankDownBinding.variants.forEach(function(b){if(!(b.from>0&&b.from<18&&b.to===b.from-1&&b.rp>=RP[b.to]&&b.rp<RP[b.from]))console.warn('S53 binding violates the ladder',b)});
function card(i,role){var r=rung(i),cur=role==='new';
return '<div class="card '+role+'" role="group" aria-label="'+(cur?'Current rank: ':'Previous rank: ')+label(r)+'"><div class="tag">'+(cur?'CURRENT':'PREVIOUS')+'</div><div class="rung '+role+'"><div class="sh"><div class="sd"><span class="slot">'+r.en[0]+'</span></div></div><div class="rtxt"><div class="nm"><b>'+r.en+'</b><em lang="ar" dir="rtl">'+r.ar+'</em></div><div class="r2"><span class="dv" aria-label="Division '+r.div+'">'+r.div+'</span><span class="bd">STARTS AT <span class="n">'+fmt(r.rp)+'</span> RP</span></div></div></div></div>'}
var NOTE={v2:'Gold I is the top division of Gold.'};
function build(b){var f=rung(b.from),t=rung(b.to),el=document.getElementById(b.id);
var say='Below <b>'+f.en+' '+f.div+'</b> at <span class="n">'+fmt(f.rp)+'</span> RP — now <b>'+t.en+' '+t.div+'</b>.';
el.innerHTML='<div class="top"><div class="kick">Rank change</div></div><div class="pair">'+card(b.from,'prev')+'<div class="link"><i></i></div>'+card(b.to,'new')+'</div><div class="foot"><div><p class="st">'+say+'</p>'+(NOTE[b.id]?'<p class="nt">'+NOTE[b.id]+'</p>':'')+'</div><div class="rpb"><small>FINAL RP</small><span class="n">'+fmt(b.rp)+'</span></div></div>'}
window.rankDownBinding.variants.forEach(build);
function play(el){el.classList.remove('play');void el.offsetWidth;el.classList.add('play')}
[].slice.call(document.querySelectorAll('.scr[data-play]')).forEach(play);
[].forEach.call(document.querySelectorAll('[data-replay]'),function(b){b.addEventListener('click',function(){play(document.getElementById(b.getAttribute('data-replay')))})});
})();
