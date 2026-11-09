#version 150
in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec3 direction;
void main() {
 // Interpolate the cube-space position first, then normalize per fragment.
 // Per-vertex normalization bends each face independently and can expose
 // rectangular seams where the six sky-cube faces meet.
 direction=Position;
 gl_Position=ProjMat*ModelViewMat*vec4(Position,1.0);
}
