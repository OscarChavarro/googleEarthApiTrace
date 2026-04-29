#version 410 core

uniform sampler2D sTexture;
uniform int withTexture;

in vec4 vertexColor;
in vec2 uvTextureCoordinate;
layout(location = 0) out vec4 fragColor;

void main()
{
    if ( withTexture > 0 ) {
        fragColor = texture(sTexture, uvTextureCoordinate) * vertexColor;
    }
    else {
        fragColor = vertexColor;
    }
}
