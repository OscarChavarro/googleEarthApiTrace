#version 410 core

uniform mat4 modelViewProjectionLocal;
uniform mat4 modelViewLocal;

layout(location = 0) in vec4 PObject;
layout(location = 2) in vec2 uvVertexTextureCoordinate;

out vec3 PGlobal;
out vec2 uvTextureCoordinate;

void main()
{
    vec4 transformed = modelViewLocal * PObject;
    PGlobal = transformed.xyz;
    uvTextureCoordinate = uvVertexTextureCoordinate;
    gl_Position = modelViewProjectionLocal * PObject;
}
