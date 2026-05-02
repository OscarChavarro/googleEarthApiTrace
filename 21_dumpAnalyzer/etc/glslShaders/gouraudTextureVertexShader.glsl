#version 410 core

uniform mat4 modelViewProjectionLocal;
uniform mat4 modelViewLocal;
uniform mat4 modelViewITLocal;
uniform vec3 cameraPositionGlobal;
uniform int numberOfLights;
uniform vec3 lightPositionsGlobal[8];
uniform vec3 lightColorsGlobal[8];
uniform int withTexture;

layout(location = 0) in vec4 PObject;
layout(location = 1) in vec3 NObject;
layout(location = 2) in vec2 uvVertexTextureCoordinate;

uniform vec3 ambientColor;
uniform vec3 diffuseColor;
uniform vec3 specularColor;
uniform float phongExponent;

out vec4 vertexColor;
out vec2 uvTextureCoordinate;

void main()
{
    vec4 PGlobal = modelViewLocal * PObject;
    vec3 N = normalize((modelViewITLocal * vec4(NObject, 0.0)).xyz);
    vec3 V = normalize(cameraPositionGlobal - PGlobal.xyz);

    vec3 ambientTerm = ambientColor;
    vec3 diffuseTerm = vec3(0.0);
    vec3 specularTerm = vec3(0.0);

    for ( int i = 0; i < numberOfLights; i++ ) {
        vec3 L = normalize(lightPositionsGlobal[i] - PGlobal.xyz);
        vec3 R = reflect(-L, N);
        diffuseTerm += lightColorsGlobal[i] * diffuseColor * max(dot(N, L), 0.0);
        specularTerm += lightColorsGlobal[i] * specularColor *
                        pow(max(dot(R, V), 0.0), phongExponent);
    }

    vertexColor = vec4(ambientTerm + diffuseTerm + specularTerm, 1.0);
    uvTextureCoordinate = uvVertexTextureCoordinate;
    gl_Position = modelViewProjectionLocal * PObject;
}
