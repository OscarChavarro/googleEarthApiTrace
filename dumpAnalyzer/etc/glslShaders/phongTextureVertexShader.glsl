#version 410 core

uniform mat4 modelViewProjectionLocal;
uniform mat4 modelViewLocal;
uniform mat4 modelViewITLocal;
uniform vec3 cameraPositionGlobal;
uniform int withTexture;

layout(location = 0) in vec4 PObject;
layout(location = 1) in vec3 NObject;
layout(location = 2) in vec2 uvVertexTextureCoordinate;

out vec3 PGlobal;
out vec3 N;
out vec3 V;
out vec2 uvTextureCoordinate;

void main()
{
    vec4 transformed = modelViewLocal * PObject;
    PGlobal = transformed.xyz;
    N = normalize((modelViewITLocal * vec4(NObject, 0.0)).xyz);
    V = normalize(cameraPositionGlobal - PGlobal);
    uvTextureCoordinate = uvVertexTextureCoordinate;
    gl_Position = modelViewProjectionLocal * PObject;
}
