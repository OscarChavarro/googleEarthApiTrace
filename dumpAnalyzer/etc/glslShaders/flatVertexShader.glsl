#version 410 core

uniform mat4 modelViewProjectionLocal;
uniform mat4 modelViewLocal;

layout(location = 0) in vec4 PObject;

out vec3 PGlobal;

void main()
{
    vec4 transformed = modelViewLocal * PObject;
    PGlobal = transformed.xyz;
    gl_Position = modelViewProjectionLocal * PObject;
}
