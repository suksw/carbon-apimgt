
import React from 'react';
import { FormattedMessage } from 'react-intl';
import LandingMenuItem from '../components/LandingMenuItem';
import LandingMenu from '../components/LandingMenu';

const GraphqlAPIMenu = (props) => {
    const { icon } = props;
    return (
        <LandingMenu
            title={(
                <FormattedMessage
                    id='Apis.Listing.SampleAPI.SampleAPI.graphql.api'
                    defaultMessage='GraphQL'
                />
            )}
            icon={icon}
        >
            <LandingMenuItem
                id='itest-id-create-default'
                linkTo='/apis/create/rest'
                helperText={(
                    <FormattedMessage
                        id='Apis.Listing.SampleAPI.SampleAPI.graphql.import.sdl.content'
                        defaultMessage='Use an existing definition'
                    />
                )}
            >
                <FormattedMessage
                    id='Apis.Listing.SampleAPI.SampleAPI.graphql.import.sdl.title'
                    defaultMessage='Import GraphQL SDL'
                />
            </LandingMenuItem>


        </LandingMenu>
    );
};

export default GraphqlAPIMenu;
