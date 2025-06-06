lo=11;
 var b=setInterval(function nam(){
 if(document.getElementById(lo).style.backgroundColor=='blue')
 document.getElementById(lo).style.backgroundColor='black';
 do{
 lo=num(11,230);
 } while(document.getElementById(lo).style.backgroundColor=='green' || document.getElementById(lo).style.backgroundColor=='red' || document.getElementById(lo).style.backgroundColor=='purple' || document.getElementById(da).style.backgroundColor=='brown');
 document.getElementById(lo).style.backgroundColor='blue';
 },5000);
 var ti=6,de=0;
 var ot=setInterval(function out(){
	 if(de!=0)
	 ti-=1;
 },1000);
 var lpo=11;
 var p=setInterval(function nm(){
 if(document.getElementById(lpo).style.backgroundColor=='purple')
 document.getElementById(lpo).style.backgroundColor='black';
 do{
 lpo=num(11,230);
 } while(document.getElementById(lpo).style.backgroundColor=='green' || document.getElementById(lpo).style.backgroundColor=='red' || document.getElementById(lpo).style.backgroundColor=='blue' || document.getElementById(da).style.backgroundColor=='brown');
 document.getElementById(lpo).style.backgroundColor='purple';
 },14000);
 var da=0,bo=num(11,230);
 window.onload=function(){
 var a='';
 for(var i=11;i<=230;i++){
 a=document.getElementById('box').innerHTML=a+'<div id="'+i+'" class="sbox"></div>';
 }
 for(var i=0;i<=80;i++){
 da=num(11,230);
 if(da!=31){
 document.getElementById(da).style.backgroundColor='red';
 }
 }
 document.getElementById(bo).style.backgroundColor='brown';
 };
 var st=setInterval(function(){
	 da=0;
	 for(var bl=11;bl<=230;bl++){
		 if(document.getElementById(bl).style.backgroundColor=='red'){
		 document.getElementById(bl).style.backgroundColor='black';}
	 }
 for(var i=0;i<=80;i++){
 da=num(11,230);
 if(da!=31 && document.getElementById(da).style.backgroundColor!='green' && document.getElementById(da).style.backgroundColor!='purple' && document.getElementById(da).style.backgroundColor!='blue' && document.getElementById(da).style.backgroundColor!='brown'){
 document.getElementById(da).style.backgroundColor='red';
 }
 }
 },30000);
 var p,i=0,but=0,sc=0,li=5;
 document.onkeydown=function(){
	 var co=window.event.keyCode;
		move(co);
	}
 function num(min,max){
 return Math.floor(Math.random() * (max-min+1))+min;
 }
 var ch=0;
 function move(co){
		if(li!=0){
		document.getElementById("p").innerHTML="PLAY GAME";
		if(but==0){
		document.getElementById("31").style.backgroundColor='green';
		but++;}
		else{
			for(i=11;i<=230;i++){
			if(document.getElementById(i).style.backgroundColor=='green'){
				document.getElementById(i).style.backgroundColor='black';
				if(co==37){
				i=i-1;
				if(i<=10 || i==30 || i==50 || i==70 || i==90 || i==110 || i==130 || i==150 || i==170 || i==190 || i==210)
				i=i+20;}
				if(co==38){
				i=i-20;
				if(i<=10)
				i=i+220;
				}
				if(co==39){
				i=i+1;
				if(i>230 || i==31 || i==51 || i==71 || i==91 || i==111 || i==131 || i==151 || i==171 || i==191 || i==211)
				i=i-20;
				}
				if(co==40){
				i=i+20;
				if(i>230)
				i=i-220;
				}
				if(document.getElementById(i).style.backgroundColor=='red'){
				document.getElementById("p").innerHTML="GAME OVER!!!";
				document.getElementById("li").innerHTML=--li;
				but=0;
				de=0;
				ti=6;
				document.getElementById("pl").innerHTML="energy";
				ch=0;
				}
				else{
				if(document.getElementById(i).style.backgroundColor=='blue'){
				sc+=5;
				document.getElementById("sc").innerHTML=sc;}
				if(document.getElementById(i).style.backgroundColor=='purple'){
					ch+=1;
					document.getElementById("pl").innerHTML=ch;
					if(ch==3){
					de=1;
					ti=5;
					ch=0;}
				sc+=10;
				document.getElementById("sc").innerHTML=sc;
				}
				if(document.getElementById(i).style.backgroundColor=='brown' && ti==6){
					document.getElementById("li").innerHTML=--li;
					document.getElementById(i).style.backgroundColor='brown';
					but=0;
					col='brown';}
					else
						col='green';
				if(document.getElementById(i).style.backgroundColor=='brown' && ti<6){
					de=0;
					ti=6;
				document.getElementById("p").innerHTML="PLAY GAME";
					bo=num(11,230);
					sc+=2;
					document.getElementById("sc").innerHTML=sc;}
				document.getElementById(i).style.backgroundColor=col;}
				if(li==0){
				clearInterval(b);
				clearInterval(p);
				clearInterval(st);
				clearInterval(bot);
				document.getElementById("p").innerHTML="EVERYTHING IS FINISHED AND YOU NO LONGER EXIST";}
			}
		}
	}
}
}
var po=0,pb;
var bot=setInterval(function bott(){
	var flr=0,pogr=30,flc=0,pobr=30,pobl=11,pogl=11;
	if(ti==0){
		de=0;
		ti=6;
		document.getElementById("pl").innerHTML="energy";
	}
	if(ti<6)
		document.getElementById("pl").innerHTML=ti;
	pb=bo;
	for(i=11;i<=230;i++){
		if(document.getElementById(i).style.backgroundColor=='green'){
			flc=flr=0;
			break;
		}
		else{
			flc=flr=1;
		}
	}
	if(i==231)
		i=bo;
 do{
	 if(i<=pogr){
		 flr=1;
	 }
	 else{
		 pogr=pogr+20;
		 pogl=pogl+20;
	 }
	 } while(flr==0);
 do{
	 if(bo<=pobr){
		 flc=1;
		 }
		 else{
			 pobr=pobr+20;
			 pobl=pobl+20;
			 }
	 }while(flc==0);
	 if(pogr<pobr && pogl<pobl){
		 bo=bo-20;
		movb();
	 }
	 if(pogr>pobr && pogl>pobl){
		 bo=bo+20;
		movb();
	 }
	 if(pogr==pobr && pogl==pobl){
		 if(i>bo && i!=231){
			 bo=bo+1;
			 movb();
		 }
		 if(i<bo){
			 bo=bo-1;
			 movb();
		 }
	 }
 },570);
 var red=0,pol=0;
 
 function movb(){
	 if(document.getElementById(bo).style.backgroundColor=='green' && ti==6){
		 document.getElementById("li").innerHTML=--li;
		 document.getElementById("p").innerHTML="PLAY GAME";
		 document.getElementById("pl").innerHTML="energy";
		 ch=0;
		 but=0;
	 }
	 if(document.getElementById(bo).style.backgroundColor=='green' && ti<6){
		 bo=num(11,230);
		 sc+=2;
		 document.getElementById("sc").innerHTML=sc;
		 document.getElementById(pb).style.backgroundColor='green';
		 document.getElementById(bo).style.backgroundColor='brown';
		 de=0;
		ti=6;
		document.getElementById("pl").innerHTML="energy";
		 }
		 else
		 {document.getElementById(pb).style.backgroundColor='black';
		 document.getElementById(bo).style.backgroundColor='brown';
		 }
	 if(li==0){
				clearInterval(b);
				clearInterval(p);
				clearInterval(st);
				clearInterval(bot);
				document.getElementById("p").innerHTML="EVERYTHING IS FINISHED AND YOU NO LONGER EXIST";
			}
 }