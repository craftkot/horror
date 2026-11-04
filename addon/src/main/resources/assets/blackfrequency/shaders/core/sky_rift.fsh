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
 float t=GameTime*.012;
 float f=clamp(RiftStrength,0.,1.);
 float green=clamp(GreenPhase,0.,1.);
 // Avoid all fracture and Voronoi work while the catastrophe is inactive.
 // A transparent pixel preserves the underlying vanilla sky exactly.
 if(f<=0.0001){
  fragColor=vec4(0.0);
  return;
 }
 float longitude=atan(n.z,n.x);
 // One continuous jagged tear, animated in world direction. Integer
 // harmonics remain seamless at -PI/PI and across sky-cube faces.
 float spineY=.12+.28*sin(2.*longitude+t*.09)
              +.09*sin(7.*longitude-t*.13)
              +.02*sin(19.*longitude+t*.21);
 float distortion=.018*sin(33.*longitude+4.*n.y+t*.61)
                 +.010*sin(79.*longitude-19.*n.y-t*.43);
 float spineDistance=abs(n.y-spineY-distortion);
 // A slow irregular surge widens the tear without extra texture lookups.
 // Keep the minimum width positive to avoid flickering or inverted edges.
 float surge=.5+.5*sin(t*.73+2.*sin(longitude*3.+t*.17));
 float coreWidth=mix(.014,.027,surge);
 float spineCore=1.-smoothstep(coreWidth,coreWidth+.027,spineDistance);
 float spineGlow=exp(-spineDistance*mix(19.,13.,surge));
 float corona=exp(-spineDistance*7.);
 // Five finite side fractures grow from the main tear. Wrapped angular
 // distance avoids a discontinuity where longitude changes sign.
 float branches=0.,branchGlow=0.;
 for(int i=0;i<5;i++){
  float anchor=-2.55+float(i)*1.13;
  float side=(i%2==0)?1.:-1.;
  float along=atan(sin(longitude-anchor),cos(longitude-anchor))*side;
  float reach=.54+float(i%3)*.09;
  float window=smoothstep(0.,.065,along)*(1.-smoothstep(reach-.12,reach,along));
  float root=.12+.28*sin(2.*anchor+t*.09)
             +.09*sin(7.*anchor-t*.13)
             +.02*sin(19.*anchor+t*.21);
  // Match the main tear's jagged distortion at the branch origin.
  // Without this offset, side cracks visibly detach as the spine moves.
  // The spine distortion depends on its own Y coordinate. Refine the
  // branch attachment point to follow that implicit curve more closely.
  float rootDistortion=.018*sin(33.*anchor+4.*root+t*.61)
                      +.010*sin(79.*anchor-19.*root-t*.43);
  float refinedY=root+rootDistortion;
  rootDistortion=.018*sin(33.*anchor+4.*refinedY+t*.61)
                +.010*sin(79.*anchor-19.*refinedY-t*.43);
  // Fade branch-only oscillations in at the root. Otherwise their
  // nonzero phase offsets make the branch miss the main tear at along=0.
  float bendIn=smoothstep(0.,.12,along);
  float branchY=root+rootDistortion+side*.43*along
                +bendIn*.032*sin(along*15.+t*.27+float(i));
  float dist=abs(n.y-branchY-bendIn*.016*sin(along*39.+t*.5+float(i)*2.));
  // Offset each branch's pulse so the fractures do not brighten in unison.
  // Preserve a nonzero core width and avoid extra cell searches.
  float branchSurge=.5+.5*sin(t*(.49+.07*float(i))+along*8.+float(i)*2.3);
  float branchWidth=mix(.008,.016,branchSurge);
  branches=max(branches,window*(1.-smoothstep(branchWidth,branchWidth+.019,dist)));
  branchGlow=max(branchGlow,window*exp(-dist*mix(32.,21.,branchSurge)));
 }
 // A separate, high-altitude fracture keeps the catastrophe visible when
 // the main tear slips behind hills. Angular harmonics stay seamless across
 // the longitude wrap, and this adds no more Voronoi cell searches.
 float upperY=.43+.14*sin(3.*longitude-t*.075+1.4)
             +.045*sin(11.*longitude+t*.17);
 float upperDistance=abs(n.y-upperY-.016*sin(41.*longitude-t*.39));
 // The upper fracture pulses independently, so the horizon does not
 // brighten in lockstep with the main and underside tears.
 float upperSurge=.5+.5*sin(t*.43+2.4*sin(3.*longitude+t*.12));
 float upperWidth=mix(.014,.024,upperSurge);
 float upperCore=1.-smoothstep(upperWidth,upperWidth+.026,upperDistance);
 float upperGlow=exp(-upperDistance*mix(15.,11.,upperSurge));
 float upperCorona=exp(-upperDistance*6.);
 // A lower-hemisphere tear makes the new underside of the sky cube
 // visibly fractured instead of showing only the upper sky pattern.
 // It uses periodic angular harmonics and no additional cell searches.
 float lowerY=-.58+.11*sin(4.*longitude+t*.11+2.1)
             +.035*sin(13.*longitude-t*.19);
 float lowerDistance=abs(n.y-lowerY-.012*sin(37.*longitude+t*.31));
 // Let the underside fracture breathe out of phase with the upper tear.
 // The strictly positive width prevents inverted smoothstep edges.
 float lowerSurge=.5+.5*sin(t*.57+3.*sin(2.*longitude-t*.13));
 float lowerWidth=mix(.012,.023,lowerSurge);
 float lowerCore=1.-smoothstep(lowerWidth,lowerWidth+.023,lowerDistance);
 float lowerGlow=exp(-lowerDistance*mix(19.,14.,lowerSurge));
 float lowerCorona=exp(-lowerDistance*8.);
 // Sparse fine filaments, not the previous full-screen Voronoi patchwork.
 // Three 3x3 cell searches instead of nine: 27 vs 81 cell evaluations.
 vec3 weights=pow(abs(n),vec3(4.));
 weights/=max(dot(weights,vec3(1.)),.0001);
 float cellDistance=dot(vec3(cells(n.yz,7.),cells(n.xz,7.),cells(n.xy,7.)),weights);
 float filaments=1.-smoothstep(.008,.023,cellDistance);
 float pulse=.86+.14*sin(t*2.7+longitude*11.);
 vec3 background=vec3(.006,.003,.014);
 vec3 violet=vec3(.16,.032,.26);
 vec3 edge=vec3(.72,.24,1.0);
 vec3 color=background+violet*(corona*.24+upperCorona*.19+lowerCorona*.18);
 color+=edge*(spineGlow*.96+branchGlow*.78+upperGlow*.80+lowerGlow*.72+filaments*.07)*pulse;
 // A near-black interior makes the central fracture read as an opening.
 color=mix(color,vec3(.0003,.00015,.001),spineCore*.98);
 color=mix(color,vec3(.0003,.00015,.001),branches*.90);
 color=mix(color,vec3(.0003,.00015,.001),upperCore*.96);
 color=mix(color,vec3(.0003,.00015,.001),lowerCore*.96);
 // The green transition is a separate phase, not a persistent tint.
 color=mix(color,vec3(.002,.011,.009),green);
 // Side fractures must contribute to alpha as well as RGB; otherwise
 // their glow fades against the unmodified world behind the sky overlay.
 float fractureCoverage=max(max(spineCore,upperCore),max(lowerCore,branches));
 float opacity=clamp(f*(.87+.06*filaments+.12*fractureCoverage),0.,.99);
 fragColor=vec4(color,opacity);
}
