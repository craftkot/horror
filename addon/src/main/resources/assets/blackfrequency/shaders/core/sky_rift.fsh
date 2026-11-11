#version 150
in vec3 direction;
uniform float RiftStrength;
uniform float GameTime;
uniform float GreenPhase;
out vec4 fragColor;
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
vec2 seed(vec2 p) { return vec2(hash(p),hash(p+19.37)); }
float fractureRim(float distance,float coreWidth) {
 // Keep a narrow luminous border just outside the near-black fracture core.
 float inner=smoothstep(coreWidth-.004,coreWidth+.002,distance);
 float outer=1.-smoothstep(coreWidth+.006,coreWidth+.022,distance);
 return inner*outer;
}
float cells(vec2 uv,float scale) {
 vec2 p=uv*scale,g=floor(p); float a=1e6,b=1e6;
 for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++){
  vec2 c=g+vec2(x,y),s=c+.2+.6*seed(c);
  // Compare squared distances: ordering is unchanged, but avoids
  // a square root for every candidate in all three Voronoi searches.
  vec2 delta=p-s;
  float d=dot(delta,delta);
  if(d<a){b=a;a=d;}else if(d<b)b=d;
 }
 // Only the two winning distances need square roots (6 vs 27 per pixel).
 return sqrt(b)-sqrt(a);
}
void main(){
 // A zero-length or non-finite interpolated direction must not generate
 // NaNs in atan(), Voronoi coordinates, or the final sky color.
 float directionLength=length(direction);
 if(isnan(directionLength)||isinf(directionLength)||directionLength<0.000001){
  fragColor=vec4(0.0);
  return;
 }
 vec3 n=direction/directionLength;
 // Invalid client uniforms must not poison the entire fragment with NaNs.
 // Preserve normal values exactly; fall back only for non-finite inputs.
 float safeTime=(isnan(GameTime)||isinf(GameTime))?0.:GameTime;
 float safeStrength=(isnan(RiftStrength)||isinf(RiftStrength))?0.:RiftStrength;
 float safeGreen=(isnan(GreenPhase)||isinf(GreenPhase))?0.:GreenPhase;
 float t=safeTime*.012;
 float f=clamp(safeStrength,0.,1.);
 float green=clamp(safeGreen,0.,1.);
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
 float branches=0.,branchGlow=0.,branchRim=0.;
 for(int i=0;i<5;i++){
  float anchor=-2.55+float(i)*1.13;
  float side=(i%2==0)?1.:-1.;
  float along=atan(sin(longitude-anchor),cos(longitude-anchor))*side;
  float reach=.54+float(i%3)*.09;
  // Include the branch root in its mask: smoothstep(0,...) previously
  // erased the first pixels and left a visible gap at the attachment.
  float window=smoothstep(-.045,0.,along)*(1.-smoothstep(reach-.12,reach,along));
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
  branchRim=max(branchRim,window*fractureRim(dist,branchWidth));
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
 // A third, high-altitude tear gives the catastrophe a visible crown when
 // the player looks above the horizon. It reuses angular harmonics and adds
 // no Voronoi searches or world/chunk work.
 float crownY=.78+.09*sin(5.*longitude+t*.06+.7)
             +.025*sin(17.*longitude-t*.17);
 float crownDistance=abs(n.y-crownY-.010*sin(47.*longitude+t*.37));
 float crownSurge=.5+.5*sin(t*.51+2.1*sin(4.*longitude-t*.11));
 float crownWidth=mix(.009,.018,crownSurge);
 float crownCore=1.-smoothstep(crownWidth,crownWidth+.022,crownDistance);
 float crownGlow=exp(-crownDistance*mix(20.,14.,crownSurge));
 float crownCorona=exp(-crownDistance*7.5);
 // A radial scar opens directly above the player. The old crown stopped
 // below the pole, leaving the exact-zenith view almost completely empty.
 // Six jagged spokes share the existing sky direction; no extra samplers,
 // Voronoi searches, world queries, or chunk work are introduced.
 float zenithRadius=length(n.xz);
 float zenithAngle=zenithRadius<.0001?0.:atan(n.z,n.x);
 float zenithSpokes=abs(sin(zenithAngle*6.
                   +.22*sin(zenithAngle*3.-t*.18)
                   +.12*sin(zenithRadius*42.-t*.7)));
 float zenithDistance=zenithSpokes*zenithRadius;
 float zenithWindow=(1.-smoothstep(.27,.46,zenithRadius))*smoothstep(.72,.9,n.y);
 float zenithWidth=mix(.006,.012,crownSurge);
 float zenithCore=zenithWindow*(1.-smoothstep(zenithWidth,zenithWidth+.014,zenithDistance));
 float zenithGlow=zenithWindow*exp(-zenithDistance*24.);
 float zenithCorona=zenithWindow*exp(-zenithRadius*5.5);
 float zenithPulse=.82+.18*sin(t*2.1+zenithAngle*7.);
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
 float majorGlow=max(spineGlow,max(branchGlow,max(upperGlow,max(lowerGlow,crownGlow))));
 float fractureField=max(majorGlow,zenithGlow);
 // The filament mask is exactly zero at and below .025. Skip all three
 // 3x3 Voronoi searches outside the tear influence instead of calculating
 // 27 cell candidates for fragments whose filament contribution is zero.
 float filaments=0.;
 if(fractureField>.025){
  // Fourth-power axis weights use two squarings; no general pow per fragment.
  vec3 weights=abs(n);
  weights*=weights;
  weights*=weights;
  weights/=max(dot(weights,vec3(1.)),.0001);
  float cellDistance=dot(vec3(cells(n.yz,7.),cells(n.xz,7.),cells(n.xy,7.)),weights);
  float filamentMask=smoothstep(.025,.18,fractureField);
  filaments=(1.-smoothstep(.008,.023,cellDistance))*filamentMask;
 }
 float pulse=.86+.14*sin(t*2.7+longitude*11.);
 float spineRim=fractureRim(spineDistance,coreWidth);
 float upperRim=fractureRim(upperDistance,upperWidth);
 float lowerRim=fractureRim(lowerDistance,lowerWidth);
 float crownRim=fractureRim(crownDistance,crownWidth);
 float zenithRim=zenithWindow*fractureRim(zenithDistance,zenithWidth);
 vec3 background=vec3(.010,.002,.022);
 vec3 violet=vec3(.16,.032,.26);
 vec3 edge=vec3(.72,.24,1.0);
 vec3 color=background+violet*(corona*.24+upperCorona*.19+lowerCorona*.18+crownCorona*.16+zenithCorona*.11);
 // Fast, localized violet lightning travels along the three major tears.
 // Modulate existing glow masks rather than adding more Voronoi searches.
 float lightningWave=sin(longitude*43.-t*5.3+sin(longitude*9.+t*.7)*2.);
 float lightningFlash=pow(max(lightningWave,0.),12.);
 float lightning=lightningFlash*majorGlow*(1.-green);
 color+=edge*(spineGlow*.96+branchGlow*.78+upperGlow*.80+lowerGlow*.72+crownGlow*.74+zenithGlow*.62+filaments*.07)*pulse;
 color+=vec3(.19,.035,.34)*lightning*.55;
 // A near-black interior makes the central fracture read as an opening.
 color=mix(color,vec3(.0003,.00015,.001),spineCore*.98);
 color=mix(color,vec3(.0003,.00015,.001),branches*.90);
 color=mix(color,vec3(.0003,.00015,.001),upperCore*.96);
 color=mix(color,vec3(.0003,.00015,.001),lowerCore*.96);
 color=mix(color,vec3(.0003,.00015,.001),crownCore*.93);
 color=mix(color,vec3(.0003,.00015,.001),zenithCore*.94);
 // A moving violet rim restores edge contrast around the dark openings.
 // It adds no texture reads or Voronoi searches and keeps the tear cores dark.
 float rimPulse=.78+.22*sin(t*3.1+longitude*23.+.8*sin(longitude*5.-t*.4));
 float rimLight=spineRim*.92+branchRim*.76+upperRim*.84+lowerRim*.72+crownRim*.88+zenithRim*.94*zenithPulse;
 color+=edge*rimLight*(1.08*rimPulse);
 // The green transition is a separate phase, not a persistent tint.
 color=mix(color,vec3(.002,.011,.009),green);
 // Side fractures must contribute to alpha as well as RGB; otherwise
 // their glow fades against the unmodified world behind the sky overlay.
 float fractureCoverage=max(zenithCore,max(max(spineCore,upperCore),max(lowerCore,max(branches,crownCore))));
 // Fade the world sky almost completely behind the active catastrophe.
 // This keeps the blue vanilla sky from washing out the near-black violet void.
 // The overlay still fades with RiftStrength and is fully transparent when inactive.
 float opacity=clamp(f*(1.04+.05*filaments+.08*fractureCoverage),0.,.995);
 fragColor=vec4(color,opacity);
}
