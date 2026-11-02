#version 150
in vec3 direction;
uniform float RiftStrength;
uniform float GameTime;
uniform float GreenPhase;
out vec4 fragColor;
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
vec2 seed(vec2 p) { return vec2(hash(p),hash(p+19.37)); }
float cells(vec2 uv,float scale) {
 vec2 p=uv*scale,g=floor(p); float a=1e6,b=1e6;
 for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++){
  vec2 c=g+vec2(x,y),s=c+.2+.6*seed(c);
  float d=length(p-s); if(d<a){b=a;a=d;}else if(d<b)b=d;
 }
 return b-a;
}
void main(){
 vec3 n=normalize(direction);
 vec3 w=pow(abs(n),vec3(4.0)); w/=max(dot(w,vec3(1.)),.0001);
 float t=GameTime*.012;
 float a=cells(n.yz+vec2(sin(t)*.012,0.),4.0);
 float b=cells(n.xz+vec2(0.,cos(t)*.012),4.0);
 float c=cells(n.xy,4.0);
 float d=dot(vec3(a,b,c),w);
 float warped=dot(vec3(cells(n.yz*1.33+sin(t+n.x*14.)*.042,6.8),cells(n.xz*1.27+cos(t+n.y*17.)*.045,7.2),cells(n.xy*1.21+sin(t+n.z*11.)*.04,6.3)),w);
 d=min(d,warped*.62);
 float secondary=dot(vec3(cells(n.yz,9.),cells(n.xz,9.),cells(n.xy,9.)),w);
 // A continuous, world-direction-based fracture spine. Integer angular
 // harmonics meet at +/-PI, so the seam cannot split at sky-cube edges.
 float longitude=atan(n.z,n.x);
 float spineHeight=.12+.34*sin(2.*longitude+t*.11)+.12*sin(5.*longitude-t*.08);
 float spineDistance=abs(n.y-spineHeight);
 float spineCore=1.-smoothstep(.014,.046,spineDistance);
 float spineHalo=exp(-spineDistance*14.);
 // Keep the old cellular filaments as secondary damage, not giant patches.
 float jitter=.004*sin(t*2.+n.x*37.+n.z*29.);
 float width=.045+.04*clamp(RiftStrength,0.,1.)+jitter;
 float fissure=1.-smoothstep(width,width+.035,d);
 float branch=(1.-smoothstep(.025,.075,secondary))*.30;
 float crack=max(fissure,branch);
 float voidPocket=smoothstep(.20,.44,hash(floor(n.xz*9.)+floor(n.y*8.)))*smoothstep(.10,.28,crack);
 float halo=exp(-max(d-width,0.)*19.);
 float f=clamp(RiftStrength,0.,1.);
 float green=clamp(GreenPhase,0.,1.);
 // The world beyond the fracture is black, not a bright blue sky.
 vec3 background=vec3(.008,.005,.017);
 vec3 purple=vec3(.16,.035,.27);
 vec3 edge=vec3(.52,.19,.87);
 float pulse=.82+.18*sin(t*3.+n.x*29.);
 vec3 color=background+purple*halo*.27+edge*max(crack-voidPocket,0.)*.94*pulse;
 color=mix(color,vec3(.0003,.0002,.0015),voidPocket*.98);
 // The spine has a violet corona surrounding a nearly lightless interior.
 color+=edge*spineHalo*.64*pulse;
 color=mix(color,vec3(.0002,.0001,.001),spineCore*.98);
 color=mix(color,vec3(.002,.011,.009),green);
 // The black core stays opaque enough to read as a tear rather than a decal.
 float opacity=clamp(f*(.72+.19*crack+.18*voidPocket+.25*spineCore),0.,.99);
 fragColor=vec4(color,opacity);
}
