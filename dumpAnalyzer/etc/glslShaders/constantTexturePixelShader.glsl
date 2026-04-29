#version 410 core

uniform sampler2D sTexture;
uniform vec3 diffuseColor;

in vec2 uvTextureCoordinate;
layout(location = 0) out vec4 fragColor;

void main()
{
    vec4 baseColor = vec4(diffuseColor, 1.0);
    fragColor = baseColor * texture(sTexture, uvTextureCoordinate);
}
