#version 410 core

layout(location = 0) in vec3 PObject;
layout(location = 2) in vec2 uvVertexTextureCoordinate;

uniform mat4 modelViewProjectionLocal;

out vec2 uvTextureCoordinate;

void main()
{
    gl_Position = modelViewProjectionLocal * vec4(PObject, 1.0);
    uvTextureCoordinate = uvVertexTextureCoordinate;
}
