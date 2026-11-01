#version 150
// BLACK FREQUENCY: procedural fractures. Intended for a Forge 1.20.1 custom sky render pass.
// Inputs are supplied by the addon renderer; NOT a drop-in vanilla shaderpack.
uniform vec2 ScreenSize;
uniform float GameTime;
uniform float RiftStrength;
uniform float GreenPhase;
uniform vec3 CameraForward;
in vec2 texCoord;
out vec4 fragColor;
float hash21(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453123); }
vec2 hash22(vec2 p) { return vec2(hash21(p),hash21(p+31.71)); }
float edgeDistance(vec2 uv, float cells) {
 vec2 p=uv*cells; vec2 g=floor(p); float a=1e6,b=1e6;
 for(int j=-1;j<=1;j++) for(int i=-1;i<=1;i++) {
   vec2 cell=g+vec2(i,j);
   vec2 center=cell+0.18+0.64*hash22(cell);
   float d=length(p-center);
   if(d<a){b=a;a=d;} else if(d<b) b=d;
 }
 return b-a;
}
void main() {
 vec2 uv=texCoord*2.-1.;
 vec2 warped=uv+vec2(sin(uv.y*11.+GameTime*0.33),cos(uv.x*9.-GameTime*0.27))*0.015;
 float f=min(edgeDistance(warped,3.5),min(edgeDistance(warped*1.32+0.7,7.0)*0.65,edgeDistance(warped*2.2-0.3,13.0)*0.4));
 float open=clamp(RiftStrength,0.,1.);
 float crack=1.-smoothstep(0.008,0.05+open*0.085,f);
 float halo=exp(-max(f,0.)*15.);
 float returning=1.-clamp(GreenPhase,0.,1.);
 vec3 voidColor=vec3(0.002,0.004,0.014);
 vec3 glow=mix(vec3(0.12,0.5,1.),vec3(0.06,1.,0.39),clamp(GreenPhase,0.,1.));
 float flicker=0.84+0.16*sin(GameTime*8.+uv.x*47.);
 vec3 color=voidColor*open + glow*(crack*1.7+halo*0.18)*open*returning*flicker;
 float alpha=clamp(open*(0.40+crack*0.60),0.,1.);
 fragColor=vec4(color,alpha);
}
