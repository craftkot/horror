#version 150
in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec3 direction;
void main() {
 // Reconstruct a view-space point on the far plane from the quad corner.
 // Interpolating these points across the fullscreen quad yields a continuous
 // per-pixel ray; transform it back to world space without camera translation.
 vec4 viewPoint=inverse(ProjMat)*vec4(Position.xy,1.0,1.0);
 float safeW=abs(viewPoint.w)>0.000001?viewPoint.w:1.0;
 vec3 viewRay=viewPoint.xyz/safeW;
 direction=(inverse(ModelViewMat)*vec4(viewRay,0.0)).xyz;
 gl_Position=vec4(Position.xy,1.0,1.0);
}
