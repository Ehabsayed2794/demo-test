(function(){
var TIERS=[{k:'bronze',en:'Bronze',ar:'مبتدئ',c:'#D79369'},{k:'silver',en:'Silver',ar:'لاعب',c:'#C3CBD4'},{k:'gold',en:'Gold',ar:'معلم',c:'#E6D585'},{k:'platinum',en:'Platinum',ar:'وزير',c:'#9AE0DF'},{k:'diamond',en:'Diamond',ar:'أمير',c:'#71BFFF'},{k:'royal',en:'Royal',ar:'سلطان',c:'#D27DE6'},{k:'king',en:'King',ar:'ملك',c:'#FF8296'}];
var RP=[0,75,150,250,350,450,575,700,825,1000,1200,1400,1600,1800,2000,2250,2500,2750,3000];
var ROM={1:'I',2:'II',3:'III'};
function fmt(n){return n.toLocaleString('en-US')}
function rung(i){if(i===18)return{i:i,tier:TIERS[6],div:0,roman:'',rp:3000,name:'King'};var t=TIERS[Math.floor(i/3)],d=3-i%3;return{i:i,tier:t,div:d,roman:ROM[d],rp:RP[i],name:t.en+' '+ROM[d]}}
function chip(i,size,extra){var r=rung(i),k=r.tier.k;return '<span class="chip '+k+'" style="--t:'+r.tier.c+';--s:'+(size||56)+'px"'+(extra||'')+'>'+(k==='royal'?'<i></i>':'')+'<b>'+(k==='king'?'K':r.roman)+'</b></span>'}
function ladder(el,cur,showYou){var h='';for(var t=0;t<7;t++){var T=TIERS[t],k=t===6;h+='<div class="lt'+(k?' k':'')+'" style="--t:'+T.c+'"><div class="h"><span>'+T.en+'</span><em lang="ar" dir="rtl">'+T.ar+'</em></div><div class="r">';
var n=k?1:3;for(var j=0;j<n;j++){var i=k?18:t*3+j,r=rung(i),c=i===cur?' cur':(i<cur?' past':'');h+='<div class="rg'+c+'" data-rung="'+i+'"'+(i===cur?' aria-current="true"':'')+'><b>'+(k?'K':r.roman)+'</b><s>'+fmt(r.rp)+'</s></div>'}
h+='</div></div>'}el.className='lad';el.innerHTML=h}
window.TierLadder={TIERS:TIERS,RP:RP,rung:rung,chip:chip,render:ladder,fmt:fmt};
})();
