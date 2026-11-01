#version 150
in vec3 direction;
uniform float RiftStrength;
uniform float GameTime;
uniform float GreenPhase;
out vec4 fragColor;
float hash(vec2 p) {return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
vec2 seed(vec2 p) {return vec2(hash(p),hash(p+19.37));}
float crack(vec2 uv,float scale) {
 vec2 p=uv*scale,g=floor(p);float a=1e6,b=1e6;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
   vec2 c=g+vec2(x,y),s=c+.2+.6*seed(c);
   float d=length(p-s);
   if(d<a){b=a;a=d;}else if(d<b)b=d;
 }
 return b-a;
}
void main(){
 vec3 n=normalize(direction);
 // Planar slices blended with normal weights, no longitude seam.
 vec3 w=pow(abs(n),vec3(4.0));w/=max(dot(w,vec3(1.0)),0.0001);
 float t=GameTime*.015;
 float a=crack(n.yz+vec2(sin(t)*.008,0),5.);
 float b=crack(n.xz+vec2(0,cos(t)*.008),5.);
 float c=crack(n.xy,5.);
 float d=dot(vec3(a,b,c),w);
 float line=1.-smoothstep(.025,.13,d);
 float halo=exp(-d*12.);
 float fade=clamp(RiftStrength,0.,1.);
 float green=clamp(GreenPhase,0.,1.);
 line *= (1.0-green);
 halo *= (1.0-green);
 vec3 color=mix(vec3(.008,.013,.029),vec3(.08,.5,1.),line)+vec3(.04,.18,.32)*halo;
 color=mix(color,vec3(.002,.018,.009),green);
 fragColor=vec4(color,fade*(.16+.77*line));
}
