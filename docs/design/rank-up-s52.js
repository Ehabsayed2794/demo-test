(function(){
/* Ladder: real values — RANKING_RULES_AND_VERIFIED_DATA.md §1. No tier colours (OPEN_DECISIONS #1). */
var TIERS=[['Bronze','مبتدئ'],['Silver','لاعب'],['Gold','معلم'],['Platinum','بلاتين'],['Diamond','أمير'],['Royal','سلطان']];
var RP=[0,75,150,250,350,450,575,700,825,1000,1200,1400,1600,1800,2000,2250,2500,2750,3000];
var ROM={1:'I',2:'II',3:'III'};
var fmt=function(n){return n.toLocaleString('en-US')};
function rung(i){if(i===18)return{i:i,en:'King',ar:'ملك',div:'',rp:3000,king:true};var t=TIERS[Math.floor(i/3)];return{i:i,en:t[0],ar:t[1],div:ROM[3-i%3],rp:RP[i],king:false}}
var label=function(r){return r.en+(r.div?' '+r.div:'')+' · '+r.ar};
/* Declared binding — server-owned, the client never computes RP. Rung indices index the ladder above. rp values are SAMPLES. */
window.rankUpBinding={source:'sample',seasonId:'sample',variants:[
{id:'v1',kind:'division-step',from:6,to:7,rp:716},
{id:'v2',kind:'tier-promotion',from:8,to:9,rp:1012},
{id:'v3',kind:'reach-king',from:17,to:18,rp:3004}]};
function rungHTML(i,role,vert){var r=rung(i);return '<div class="rung '+role+'" role="group" aria-label="'+(role==='new'?'New rank: ':'Previous rank: ')+label(r)+'"><div class="sh"><div class="sd"><span class="slot">'+r.en[0]+'</span></div></div><div class="rtxt"><div class="nm"><b>'+r.en+'</b><em lang="ar" dir="rtl">'+r.ar+'</em></div><div class="r2">'+(r.div?'<span class="dv" aria-label="Division '+r.div+'">'+r.div+'</span>':'')+'<span class="bd">STARTS AT <span class="n">'+fmt(r.rp)+'</span> RP</span></div></div></div>'}
var NOTE={'v2':'Platinum begins at division III.','v3':'The highest rank on the ladder.'};
function build(b){var f=rung(b.from),t=rung(b.to),el=document.getElementById(b.id),h='<div class="top"><div class="kick">Promotion</div></div>';
var say='You reached <b>'+t.en+(t.div?' '+t.div:'')+'</b> at <span class="n">'+fmt(t.rp)+'</span> RP.';
var rpb='<div class="rpb"><small>FINAL RP</small><span class="n">'+fmt(b.rp)+'</span></div>';
if(b.id==='v3'){
h+='<div class="k3"><div class="lft">'+rungHTML(b.from,'prev')+'<div class="link"><i></i></div></div><div><div class="rung new" role="group" aria-label="New rank: '+label(t)+'"><div class="sh"><div class="sd"><span class="slot">K</span></div></div><div class="rtxt"><div class="nm"><b>King</b><em lang="ar" dir="rtl">ملك</em></div><div class="r2"><span class="bd">STARTS AT <span class="n">3,000</span> RP</span></div></div></div><p class="st">'+say+'</p></div><div class="rgt">'+rpb+'<p>'+NOTE.v3+' Further RP counts on the leaderboard only.</p></div></div>';
}else{
h+='<div class="pair">'+rungHTML(b.from,'prev')+'<div class="link"><i></i></div>'+rungHTML(b.to,'new')+'</div><div class="foot"><div><p class="st">'+say+'</p>'+(NOTE[b.id]?'<p class="nt">'+NOTE[b.id]+'</p>':'')+'</div>'+rpb+'</div>';}
el.innerHTML=h}
window.rankUpBinding.variants.forEach(build);
function play(el){el.classList.remove('play');void el.offsetWidth;el.classList.add('play')}
var frames=[].slice.call(document.querySelectorAll('.scr[data-play]'));
frames.forEach(play);
[].forEach.call(document.querySelectorAll('[data-replay]'),function(b){b.addEventListener('click',function(){play(document.getElementById(b.getAttribute('data-replay')))})});
})();
