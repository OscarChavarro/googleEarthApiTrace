#version 410 core

uniform int numberOfLights;
uniform vec3 lightColorsGlobal[8];
uniform vec3 lightPositionsGlobal[8];
uniform vec3 cameraPositionGlobal;
uniform vec3 ambientColor;
uniform vec3 diffuseColor;
uniform vec3 specularColor;
uniform float phongExponent;

in vec3 PGlobal;
layout(location = 0) out vec4 fragColor;

void main()
{
    vec3 dPosdx = dFdx(PGlobal);
    vec3 dPosdy = dFdy(PGlobal);
    vec3 normal = normalize(cross(dPosdx, dPosdy));
    vec3 viewDir = normalize(cameraPositionGlobal - PGlobal);

    if ( !gl_FrontFacing ) {
        normal = -normal;
    }

    vec3 ambientTerm = ambientColor;
    vec3 diffuseTerm = vec3(0.0);
    vec3 specularTerm = vec3(0.0);

    for ( int i = 0; i < numberOfLights; i++ ) {
        vec3 L = normalize(lightPositionsGlobal[i] - PGlobal);
        vec3 R = reflect(-L, normal);
        diffuseTerm += lightColorsGlobal[i] * diffuseColor * max(dot(normal, L), 0.0);
        specularTerm += lightColorsGlobal[i] * specularColor *
                        pow(max(dot(R, viewDir), 0.0), phongExponent);
    }

    vec3 color = ambientTerm + diffuseTerm + specularTerm;
    fragColor = vec4(color, 1.0);
}
